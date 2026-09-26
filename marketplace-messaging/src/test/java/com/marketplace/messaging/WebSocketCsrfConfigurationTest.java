package com.marketplace.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4/N3 (comprehensive repair plan §10/2.1): the guards for the stateless
 *-aware CSRF channel interceptor (the official {@code csrfChannelInterceptor}
 * bean-name override of {@code @EnableWebSocketSecurity}'s default):
 * a session CsrfToken keeps the framework's full enforcement (the
 * cookie-session flow), its absence passes the CSRF leg (the stateless
 * token flow — the JWT is an explicit credential, CSRF-immune by
 * construction, and the message authorization manager still owns the
 * CONNECT).
 */
class WebSocketCsrfConfigurationTest {

    private static final CsrfToken SESSION_TOKEN =
            new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "session-csrf-token-value");

    private ChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketCsrfConfiguration().csrfChannelInterceptor();
    }

    /**
     * The client-side of the framework's XOR CSRF wire format: a random
     * half X followed by the token XOR-ed with X, base64url-encoded — what
     * {@code XorCsrfChannelInterceptor.getTokenValue} decodes back.
     */
    private static String xorEncoded(String rawToken) {
        byte[] token = rawToken.getBytes(StandardCharsets.UTF_8);
        byte[] x = new byte[token.length];
        new SecureRandom().nextBytes(x);
        byte[] xored = new byte[token.length];
        for (int i = 0; i < token.length; i++) {
            xored[i] = (byte) (token[i] ^ x[i]);
        }
        byte[] both = new byte[2 * token.length];
        System.arraycopy(x, 0, both, 0, token.length);
        System.arraycopy(xored, 0, both, token.length, token.length);
        return Base64.getUrlEncoder().encodeToString(both);
    }

    private static Message<byte[]> connect(Map<String, Object> sessionAttributes, String csrfHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (sessionAttributes != null) {
            accessor.setSessionAttributes(sessionAttributes);
        }
        if (csrfHeader != null) {
            accessor.setNativeHeader(SESSION_TOKEN.getHeaderName(), csrfHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithoutSessionCsrfTokenPassesTheCsrfLeg() {
        // The stateless token flow — no session, no ambient credential.
        Message<?> result = interceptor.preSend(connect(null, null), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithEmptySessionAttributesPassesTheCsrfLeg() {
        // A handshake with a session that holds no token (the CsrfFilter
        // never stored one) is the stateless shape too — not a failure.
        Message<?> result = interceptor.preSend(connect(new HashMap<>(), null), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithSessionCsrfTokenAndMatchingHeaderIsEnforcedAndPasses() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), SESSION_TOKEN);

        // The honest cookie-session client sends the XOR-encoded token — the
        // exact wire format the framework's interceptor decodes (bytecode-
        // verified: base64url(X || token^X), decoded and constant-time
        // compared against the session's raw token).
        Message<?> result = interceptor.preSend(
                connect(attributes, xorEncoded(SESSION_TOKEN.getToken())), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithSessionCsrfTokenAndWrongHeaderIsRejected() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), SESSION_TOKEN);

        // The cookie-session flow under a cross-origin attack: the header
        // cannot guess the session's token — the CONNECT dies.
        assertThatThrownBy(() -> interceptor.preSend(
                connect(attributes, "forged-token"), (MessageChannel) null))
                .isInstanceOf(org.springframework.security.web.csrf.InvalidCsrfTokenException.class);
    }

    @Test
    void connectWithSessionCsrfTokenAndMissingHeaderIsRejected() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), SESSION_TOKEN);

        assertThatThrownBy(() -> interceptor.preSend(connect(attributes, null), (MessageChannel) null))
                .isInstanceOf(org.springframework.security.web.csrf.InvalidCsrfTokenException.class);
    }

    @Test
    void nonConnectMessagesPassThroughEvenWithSessionCsrfToken() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setLeaveMutable(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), SESSION_TOKEN);
        accessor.setSessionAttributes(attributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // The framework's interceptor only ever inspects CONNECT — our
        // delegation preserves that shape exactly.
        Message<?> result = interceptor.preSend(message, (MessageChannel) null);

        assertThat(result).isNotNull();
    }
}

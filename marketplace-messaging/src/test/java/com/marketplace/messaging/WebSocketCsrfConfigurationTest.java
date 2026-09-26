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

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S4/N3 (comprehensive repair plan §10/2.1): the guards for the pass-through
 * CSRF channel interceptor (the official {@code csrfChannelInterceptor}
 * bean-name override of {@code @EnableWebSocketSecurity}'s default XOR
 * enforcement). The class documents WHY pass-through is the exact semantics
 * of this architecture — see {@link WebSocketCsrfConfiguration}'s javadoc for
 * the full measured chain. What these guards pin down:
 * <ul>
 *   <li>Every message shape passes the CSRF leg — including a CONNECT whose
 *       session attributes carry the MINTED token (the state that Spring
 *       Security's own handshake interceptor produces for EVERY connection,
 *       stateless ones included — the CI round-2 measurement: four distinct
 *       minted tokens, four stateless connections). Under the default XOR
 *       interceptor that CONNECT would die demanding a header the client
 *       cannot know.</li>
 *   <li>The pass-through is not an authorization hole: the same CONNECT
 *       without a user is still rejected by the message authorization
 *       manager (the tokenless guard in the integration test), and a
 *       supplied invalid token is rejected by the JWT lifter (resource-server
 *       semantics). The CSRF bean never was the boundary — the message layer
 *       is.</li>
 * </ul>
 */
class WebSocketCsrfConfigurationTest {

    /** The minted-token state every real connection's attributes carry. */
    private static final CsrfToken MINTED_SESSION_TOKEN =
            new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "framework-minted-token-value");

    private ChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketCsrfConfiguration().csrfChannelInterceptor();
    }

    private static Message<byte[]> connect(Map<String, Object> sessionAttributes, String csrfHeader) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        if (sessionAttributes != null) {
            accessor.setSessionAttributes(sessionAttributes);
        }
        if (csrfHeader != null) {
            accessor.setNativeHeader(MINTED_SESSION_TOKEN.getHeaderName(), csrfHeader);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithoutSessionAttributesPassesTheCsrfLeg() {
        // The pure stateless flow — no session, no minted token, no ambient
        // credential. The JWT lifter + the authorization manager own this
        // CONNECT's fate.
        Message<?> result = interceptor.preSend(connect(null, null), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithEmptySessionAttributesPassesTheCsrfLeg() {
        // A handshake whose session holds nothing — the stateless shape too.
        Message<?> result = interceptor.preSend(connect(new HashMap<>(), null), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithTheMintedSessionTokenPassesWithoutDemandingAHeader() {
        // THE CI round-2 state: the framework's own handshake machinery minted
        // a token into every connection's attributes (stateless clients
        // included — they can never know its value). Under the default XOR
        // interceptor this CONNECT dies on MissingCsrfTokenException; under
        // the honest semantics it passes — the minted token is not a
        // cookie-session marker and the CSRF leg is not the boundary.
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), MINTED_SESSION_TOKEN);

        Message<?> result = interceptor.preSend(connect(attributes, null), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithTheMintedSessionTokenAndAForgedHeaderStillPassesTheCsrfLeg() {
        // The CSRF leg does not judge headers at all — a forged header is as
        // irrelevant as a missing one. Rejection of unauthenticated CONNECTs
        // belongs to the message authorization manager; rejection of invalid
        // SUPPLIED tokens belongs to the JWT lifter. (Under the old
        // delegation this died on InvalidCsrfTokenException — enforcement
        // parked in the wrong layer.)
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), MINTED_SESSION_TOKEN);

        Message<?> result = interceptor.preSend(connect(attributes, "forged-token"), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void connectWithTheMintedSessionTokenAndAnHonestXorHeaderStillPassesTheCsrfLeg() {
        // Even a client that somehow learned the minted token and sent the
        // framework's exact XOR wire format passes — nothing here is won or
        // lost on CSRF headers; the CONNECT's authentication is what the
        // authorization layer judges.
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), MINTED_SESSION_TOKEN);

        Message<?> result = interceptor.preSend(
                connect(attributes, xorEncoded(MINTED_SESSION_TOKEN.getToken())), (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    @Test
    void nonConnectMessagesPassThrough() {
        // The leg only ever saw CONNECT frames — the pass-through keeps every
        // message untouched (SEND shown; the broker flow after CONNECT never
        // consults CSRF).
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setLeaveMutable(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(CsrfToken.class.getName(), MINTED_SESSION_TOKEN);
        accessor.setSessionAttributes(attributes);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, (MessageChannel) null);

        assertThat(result).isNotNull();
    }

    /**
     * The client-side of the framework's XOR CSRF wire format (kept as the
     * honest-client probe): a random half X followed by the token XOR-ed with
     * X, base64url-encoded — what {@code XorCsrfChannelInterceptor.getTokenValue}
     * would decode back.
     */
    private static String xorEncoded(String rawToken) {
        byte[] token = rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] x = new byte[token.length];
        new java.security.SecureRandom().nextBytes(x);
        byte[] xored = new byte[token.length];
        for (int i = 0; i < token.length; i++) {
            xored[i] = (byte) (token[i] ^ x[i]);
        }
        byte[] both = new byte[2 * token.length];
        System.arraycopy(x, 0, both, 0, token.length);
        System.arraycopy(xored, 0, both, token.length, token.length);
        return java.util.Base64.getUrlEncoder().encodeToString(both);
    }
}

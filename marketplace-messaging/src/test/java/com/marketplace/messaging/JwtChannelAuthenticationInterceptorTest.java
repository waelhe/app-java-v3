package com.marketplace.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S4/N3 (comprehensive repair plan §10/2.1): the guards for the documented
 * token-based STOMP authentication interceptor — the CONNECT-frame bearer
 * token lifts the session user through the SAME JwtDecoder and converter
 * the resource-server chain uses; a user that already exists is never
 * overridden; a supplied invalid token rejects the CONNECT instead of
 * falling through to anonymous.
 */
@ExtendWith(MockitoExtension.class)
class JwtChannelAuthenticationInterceptorTest {

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private JwtAuthenticationConverter jwtAuthenticationConverter;

    @Mock
    private ObjectProvider<JwtDecoder> jwtDecoderProvider;

    @Mock
    private ObjectProvider<JwtAuthenticationConverter> converterProvider;

    @Mock
    private BearerSessionExpirationGuard expirationGuard;

    private JwtChannelAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // The full-application wiring: the providers always resolve.
        lenient().when(jwtDecoderProvider.getIfAvailable()).thenReturn(jwtDecoder);
        lenient().when(converterProvider.getIfAvailable()).thenReturn(jwtAuthenticationConverter);
        interceptor = new JwtChannelAuthenticationInterceptor(jwtDecoderProvider, converterProvider, expirationGuard);
    }

    private static Jwt jwt(String subject) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(900),
                Map.of("alg", "RS256"), Map.of("sub", subject, "roles", List.of("CONSUMER")));
    }

    private static StompHeaderAccessor connectAccessor() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    @Test
    void connectWithBearerHeaderLiftsTheJwtUserOntoTheSession() {
        Jwt jwt = jwt("subject-1");
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);
        JwtAuthenticationToken converted =
                new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_CONSUMER"));
        when(jwtAuthenticationConverter.convert(jwt)).thenReturn(converted);

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(accessor.getUser()).isInstanceOf(JwtAuthenticationToken.class);
        JwtAuthenticationToken user = (JwtAuthenticationToken) accessor.getUser();
        assertThat(user.getName()).isEqualTo("subject-1");
        assertThat(user.getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CONSUMER");
    }

    @Test
    void connectWithAnExistingUserIsNeverReauthenticated() {
        StompHeaderAccessor accessor = connectAccessor();
        // The handshake was authenticated (bearer header) or the session
        // carried a Principal — the HTTP-layer authentication owns the session.
        accessor.setUser(new TestingAuthenticationToken("http-layer-user", "password", "ROLE_USER"));
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(accessor.getUser().getName()).isEqualTo("http-layer-user");
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void connectWithoutAuthorizationHeaderPassesThroughUntouched() {
        StompHeaderAccessor accessor = connectAccessor();
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(accessor.getUser()).isNull();
        verifyNoInteractionsDecoding();
    }

    @Test
    void connectWithNonBearerAuthorizationHeaderPassesThroughUntouched() {
        StompHeaderAccessor accessor = connectAccessor();
        accessor.setNativeHeader("Authorization", "Basic dXNlcjpwYXNz");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(accessor.getUser()).isNull();
        verifyNoInteractionsDecoding();
    }

    @Test
    void connectWithInvalidSuppliedTokenRejectsTheConnect() {
        when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("expired"));

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setNativeHeader("Authorization", "Bearer bad-token");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // The resource-server semantics for a SUPPLIED bad token: reject —
        // never a silent fall-through to anonymous.
        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("Invalid WebSocket CONNECT token");
        assertThat(accessor.getUser()).isNull();
    }

    @Test
    void nonConnectMessagesPassThroughUntouched() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setLeaveMutable(true);
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        // Authentication happens on the CONNECT frame only — the documented
        // pattern ("an interceptor needs only to authenticate and set the
        // user header on the CONNECT Message").
        assertThat(accessor.getUser()).isNull();
        verifyNoInteractionsDecoding();
    }

    @Test
    void aNonJwtConverterResultIsStillLiftedAsTheSessionUser() {
        // Defense for the converter contract: the shared bean returns a
        // JwtAuthenticationToken today, but the interceptor's job is lifting
        // the Authentication — whatever shape the converter produced.
        Jwt jwt = jwt("subject-2");
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);
        TestingAuthenticationToken plain =
                new TestingAuthenticationToken("subject-2", "password", "ROLE_PROVIDER");
        when(jwtAuthenticationConverter.convert(jwt)).thenReturn(plain);

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        assertThat(accessor.getUser()).isInstanceOf(Authentication.class);
        Authentication lifted = (Authentication) accessor.getUser();
        assertThat(lifted.getName()).isEqualTo("subject-2");
        assertThat(lifted.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("ROLE_PROVIDER");
    }

    @Test
    void connectWithBearerHeaderIsInertWhenTheSecurityInfrastructureIsAbsent() {
        // The module-slice shape (messaging's own @ApplicationModuleTest): the
        // providers resolve nothing — no decoder means no token flow exists
        // there; the CONNECT stays for the message layer to decide.
        ObjectProvider<JwtDecoder> emptyDecoder = providerOf(null);
        ObjectProvider<JwtAuthenticationConverter> emptyConverter = providerOf(null);
        JwtChannelAuthenticationInterceptor sliceInterceptor =
                new JwtChannelAuthenticationInterceptor(emptyDecoder, emptyConverter, expirationGuard);

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        sliceInterceptor.preSend(message, null);

        assertThat(accessor.getUser()).isNull();
    }

    // ------------------------------------------------------------------
    // Row 10 (agent findings 2026-10-25, CWE-613): the retained principal
    // must not outlive its token — the CONNECT arms the expiration guard,
    // and every subsequent inbound frame re-validates the retained token.
    // ------------------------------------------------------------------

    @Test
    void connectWithBearerHeaderArmsTheBearerSessionExpirationGuard() {
        Jwt jwt = jwt("subject-1");
        when(jwtDecoder.decode("token-value")).thenReturn(jwt);
        when(jwtAuthenticationConverter.convert(jwt))
                .thenReturn(new JwtAuthenticationToken(jwt, List.of(() -> "ROLE_CONSUMER")));

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setSessionId("ws-17");
        accessor.setNativeHeader("Authorization", "Bearer token-value");
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        interceptor.preSend(message, null);

        // The lifted token's expiry is what schedules the session's close —
        // the browser path's only arming point (no handshake principal).
        verify(expirationGuard).bearerTokenAuthenticated("ws-17", jwt);
    }

    @Test
    void anExpiredRetainedTokenRejectsTheNextInboundFrame() {
        Jwt expired = jwt("subject-1");
        JwtAuthenticationToken retained =
                new JwtAuthenticationToken(expired, List.of(() -> "ROLE_CONSUMER"));
        when(jwtDecoder.decode("token-value")).thenThrow(new JwtException("expired"));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setUser(retained);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        // The finding's core scenario: the token expired after CONNECT, and
        // the socket kept its authority. The frame is now rejected — and the
        // framework's ERROR path closes the socket with its subscriptions
        // (verified against StompSubProtocolHandler 7.0.10).
        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(InvalidBearerTokenException.class)
                .hasMessageContaining("no longer valid");
    }

    @Test
    void aValidRetainedTokenPassesTheInboundFrame() {
        Jwt valid = jwt("subject-1");
        JwtAuthenticationToken retained =
                new JwtAuthenticationToken(valid, List.of(() -> "ROLE_CONSUMER"));
        when(jwtDecoder.decode("token-value")).thenReturn(valid);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setUser(retained);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verify(jwtDecoder).decode("token-value");
    }

    @Test
    void aDisconnectFrameWithAnExpiredRetainedTokenStillReachesTheBroker() {
        // Review round 1: DISCONNECT grants no authority — it only tears
        // the session's state down. Routing it through the retained-token
        // re-validation would reject the frame exactly when the socket
        // outlived exp, and the broker would never see the DISCONNECT:
        // the session's subscriptions stay in its registry with no clean
        // teardown. The frame must pass untouched, without a decode.
        Jwt expired = jwt("subject-1");
        JwtAuthenticationToken retained =
                new JwtAuthenticationToken(expired, List.of(() -> "ROLE_CONSUMER"));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        accessor.setLeaveMutable(true);
        accessor.setUser(retained);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verify(jwtDecoder, never()).decode(any());
    }

    @Test
    void anInboundFrameUnderTheTranslatedIdentityRevalidatesTheWrappedToken() {
        // After the identity translator, the session's user is
        // WebSocketUserIdentity wrapping the token authentication — the
        // re-validation must reach the WRAPPED token.
        Jwt wrapped = jwt("subject-1");
        JwtAuthenticationToken token =
                new JwtAuthenticationToken(wrapped, List.of(() -> "ROLE_CONSUMER"));
        WebSocketUserIdentity translated = new WebSocketUserIdentity(token, "0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220");
        when(jwtDecoder.decode("token-value")).thenThrow(new JwtException("expired"));

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/topic/notifications/0e0b1e6d-6b1e-4f44-b3a3-c4f00d31d220");
        accessor.setUser(translated);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, null))
                .isInstanceOf(InvalidBearerTokenException.class);
        verify(jwtDecoder).decode("token-value");
    }

    @Test
    void anInboundFrameUnderASessionPrincipalPassesThroughUntouched() {
        // The cookie/session flow (chain 3) — a non-token principal has no
        // bearer token to re-validate; session expiry governs that flow.
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setUser(new TestingAuthenticationToken("session-user", "password", "ROLE_USER"));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, null)).isSameAs(message);
        verifyNoInteractionsDecoding();
    }

    @Test
    void anInboundFrameIsInertWhenTheSecurityInfrastructureIsAbsent() {
        ObjectProvider<JwtDecoder> emptyDecoder = providerOf(null);
        ObjectProvider<JwtAuthenticationConverter> emptyConverter = providerOf(null);
        JwtChannelAuthenticationInterceptor sliceInterceptor =
                new JwtChannelAuthenticationInterceptor(emptyDecoder, emptyConverter, expirationGuard);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setLeaveMutable(true);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setUser(new JwtAuthenticationToken(jwt("subject-1"), List.of(() -> "ROLE_CONSUMER")));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThat(sliceInterceptor.preSend(message, null)).isSameAs(message);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.lenient().when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private void verifyNoInteractionsDecoding() {
        lenient().when(jwtDecoder.decode(any())).thenThrow(new AssertionError("must not decode"));
    }
}

package com.marketplace.messaging;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    private JwtChannelAuthenticationInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtChannelAuthenticationInterceptor(jwtDecoder, jwtAuthenticationConverter);
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

    private void verifyNoInteractionsDecoding() {
        lenient().when(jwtDecoder.decode(any())).thenThrow(new AssertionError("must not decode"));
    }
}

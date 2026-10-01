package com.marketplace.messaging;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserSummary;
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
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R7 (comprehensive-review-ar fix plan §4, Wave 5): the guards for the
 * WebSocket identity translation — the ONE boundary level that re-wears
 * the token principal as the stable user id
 * ({@link WebSocketUserIdentity} — the SAME authorities, the SAME Jwt as
 * the principal, the UUID as the name). Every non-resolvable shape
 * passes through untouched (fail-closed — the existing guards keep
 * rejecting, never a silent identity substitution).
 *
 * <p><b>The measured framework fact this suite pins:</b> a RE-MINTED
 * {@code JwtAuthenticationToken(jwt, authorities, name)} is value-equal
 * to the lifted one ({@code AbstractAuthenticationToken.equals} compares
 * authorities/details/credentials/principal, never the name — verified
 * against spring-security-core 7.1.1), and
 * {@code MessageHeaderAccessor.setHeader} SKIPS value-equal writes
 * (verified against spring-messaging 7.0.9) — so the official
 * name-carrying constructor can BUILD a principal but cannot REPLACE one
 * here: the replacement would be a silent no-op. The first-party boundary
 * type's identity equality is what makes the translation land — the
 * first test asserts it.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketIdentityChannelInterceptorTest {

    private static final UUID USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Mock
    private UserLookupPort userLookupPort;

    @Mock
    private ObjectProvider<UserLookupPort> userLookupPortProvider;

    private WebSocketIdentityChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        // The full-application wiring: the provider always resolves.
        lenient().when(userLookupPortProvider.getIfAvailable()).thenReturn(userLookupPort);
        interceptor = new WebSocketIdentityChannelInterceptor(userLookupPortProvider);
    }

    private static Jwt jwt(String subject) {
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(900),
                Map.of("alg", "RS256"), Map.of("sub", subject, "roles", List.of("CONSUMER")));
    }

    private static JwtAuthenticationToken token(String subject) {
        return new JwtAuthenticationToken(jwt(subject), List.of(() -> "ROLE_CONSUMER"));
    }

    private static StompHeaderAccessor connectAccessor() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setLeaveMutable(true);
        return accessor;
    }

    private static Message<byte[]> connectMessage(StompHeaderAccessor accessor) {
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithSubjectPrincipalIsTranslatedToTheStableUserId() {
        when(userLookupPort.findBySubject("user@example.com"))
                .thenReturn(Optional.of(new UserSummary(USER_ID, "user@example.com",
                        "Example User", "CONSUMER", Instant.now(), Instant.now())));

        StompHeaderAccessor accessor = connectAccessor();
        JwtAuthenticationToken lifted = token("user@example.com");
        accessor.setUser(lifted);

        interceptor.preSend(connectMessage(accessor), null);

        // The translation proof: the user header is the boundary identity
        // with the stable id as its name, delegating the SAME authorities
        // and the SAME Jwt principal — and NOT value-equal to the lifted
        // token (the header write must land; see the class javadoc's
        // measured framework fact).
        assertThat(accessor.getUser()).isInstanceOf(WebSocketUserIdentity.class);
        WebSocketUserIdentity translated = (WebSocketUserIdentity) accessor.getUser();
        assertThat(translated.getName()).isEqualTo(USER_ID.toString());
        assertThat(translated.isAuthenticated()).isTrue();
        assertThat(translated.getPrincipal()).isEqualTo(lifted.getPrincipal());
        assertThat(translated.getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("ROLE_CONSUMER");
        assertThat(translated).isNotEqualTo(lifted);
    }

    @Test
    void theTranslationRidesTheSameSubjectSeamTheRestSurfaceRides() {
        when(userLookupPort.findBySubject("login-handle")).thenReturn(Optional.of(new UserSummary(
                USER_ID, null, "Handle User", "PROVIDER", Instant.now(), Instant.now())));

        StompHeaderAccessor accessor = connectAccessor();
        accessor.setUser(token("login-handle"));

        interceptor.preSend(connectMessage(accessor), null);

        // The subject is the resolution key — a login-handle subject (the
        // non-email flow, e.g. the native login) resolves exactly like the
        // REST surface's IdentityUserProvider.findBySubject. The email
        // column (null here) is never the key.
        assertThat(((Authentication) accessor.getUser()).getName()).isEqualTo(USER_ID.toString());
        verify(userLookupPort).findBySubject("login-handle");
    }

    @Test
    void connectWithAUuidPrincipalIsLeftUntouched() {
        StompHeaderAccessor accessor = connectAccessor();
        JwtAuthenticationToken uuidNamed = token(USER_ID.toString());
        accessor.setUser(uuidNamed);

        interceptor.preSend(connectMessage(accessor), null);

        // Idempotence: a principal already carrying the stable id is never
        // re-translated (future UUID-subject tokens translate themselves).
        assertThat(accessor.getUser()).isSameAs(uuidNamed);
        verifyNoInteractions(userLookupPort);
    }

    @Test
    void connectWithAnUnresolvableSubjectPassesThroughFailClosed() {
        when(userLookupPort.findBySubject("ghost@example.com")).thenReturn(Optional.empty());

        StompHeaderAccessor accessor = connectAccessor();
        JwtAuthenticationToken lifted = token("ghost@example.com");
        accessor.setUser(lifted);

        interceptor.preSend(connectMessage(accessor), null);

        // Authenticated token, no identity row — REST's strict path throws
        // for the same state; the WebSocket path stays closed (the guards
        // keep rejecting the subject name), never a silent substitution.
        assertThat(accessor.getUser()).isSameAs(lifted);
        verify(userLookupPort).findBySubject("ghost@example.com");
    }

    @Test
    void connectWithoutAUserIsLeftUntouched() {
        StompHeaderAccessor accessor = connectAccessor();

        interceptor.preSend(connectMessage(accessor), null);

        // The anonymous CONNECT — the message layer's authorization
        // manager decides its fate; identity translation has no say.
        assertThat(accessor.getUser()).isNull();
        verifyNoInteractions(userLookupPort);
    }

    @Test
    void connectWithANonJwtPrincipalIsLeftUntouched() {
        // The module-slice shape: the slice's testing principal (or any
        // future non-token principal) is not the token flow.
        StompHeaderAccessor accessor = connectAccessor();
        TestingAuthenticationToken sessionPrincipal =
                new TestingAuthenticationToken("slice-user", "password", "ROLE_USER");
        accessor.setUser(sessionPrincipal);

        interceptor.preSend(connectMessage(accessor), null);

        assertThat(accessor.getUser()).isSameAs(sessionPrincipal);
        verifyNoInteractions(userLookupPort);
    }

    @Test
    void nonConnectMessagesPassThroughUntouched() {
        // The translation (like the authentication lift) happens on the
        // CONNECT frame only — the framework associates the CONNECT's
        // final user with every subsequent message on the session.
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/chat.sendMessage/42");
        accessor.setLeaveMutable(true);
        accessor.setUser(token("user@example.com"));

        interceptor.preSend(connectMessage(accessor), null);

        assertThat(accessor.getUser().getName()).isEqualTo("user@example.com");
        verifyNoInteractions(userLookupPort);
    }

    @Test
    void theInterceptorIsInertWhenThePortIsAbsent() {
        // The module-slice context (messaging's own @ApplicationModuleTest)
        // boots without the identity module's implementation — no
        // resolution exists there; the principal passes through.
        ObjectProvider<UserLookupPort> empty = providerOf(null);
        WebSocketIdentityChannelInterceptor sliceInterceptor =
                new WebSocketIdentityChannelInterceptor(empty);

        StompHeaderAccessor accessor = connectAccessor();
        JwtAuthenticationToken lifted = token("user@example.com");
        accessor.setUser(lifted);

        sliceInterceptor.preSend(connectMessage(accessor), null);

        assertThat(accessor.getUser()).isSameAs(lifted);
    }

    @Test
    void aSubjectThatLooksLikeAUuidNeverQueries() {
        // Even a NON-canonical uuid-shaped subject short-circuits before
        // the port — the idempotence check is the cheap path.
        StompHeaderAccessor accessor = connectAccessor();
        accessor.setUser(token(USER_ID.toString().toUpperCase()));

        interceptor.preSend(connectMessage(accessor), null);

        verify(userLookupPort, never()).findBySubject(anyString());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> provider =
                org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.lenient().when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}

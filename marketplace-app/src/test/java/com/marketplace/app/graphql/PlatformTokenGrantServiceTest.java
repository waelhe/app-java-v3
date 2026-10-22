package com.marketplace.app.graphql;

import com.marketplace.identity.spi.IdentitySpi;
import com.marketplace.identity.spi.UserAccountView;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent 1 (the Saleor 458-root campaign, PR #696 — the integrator's
 * security round): the refresh-rotation proof for the platform's own
 * password grant — the ONE-TIME-USE contract the integrator demanded:
 *
 * <ul>
 *   <li><b>Rotation:</b> {@code refresh} mints a FRESH pair (a new
 *       access token AND a successor refresh token) with the SAME
 *       principal/roles claims, riding the SAME authorization row —
 *       the official {@code OAuth2RefreshTokenAuthenticationProvider}
 *       pattern over the platform's own primitives.</li>
 *   <li><b>Replay:</b> the presented token is DEAD after the rotation —
 *       {@code findByToken(old)} answers {@code null} ever after (the
 *       Jdbc service's row-replacement semantics, modeled faithfully by
 *       the in-memory store below).</li>
 *   <li><b>Life:</b> an expired OR invalidated refresh token is
 *       rejected (the official {@code Token#isActive()} rule).</li>
 *   <li><b>Concurrency:</b> two SIMULTANEOUS rotations of the same
 *       token allow EXACTLY ONE — the loser re-reads the store after
 *       the winner replaced the row and finds the token gone.</li>
 *   <li><b>Deactivation:</b> {@code tokensDeactivateAll}'s kill (the
 *       identity owner's DELETE by principal) reaches the ROTATED
 *       successor — the row the rotation kept is the row the kill
 *       deletes.</li>
 * </ul>
 *
 * <p>No Spring context: the service's own collaborators are assembled
 * directly (the real NimbusJwtEncoder/Decoder over one generated RSA
 * key, the real InMemoryRegisteredClientRepository, and an
 * OAuth2AuthorizationService that reproduces the OFFICIAL
 * JdbcOAuth2AuthorizationService semantics the rotation relies on —
 * save-by-id REPLACES the row, findByToken matches the stored token
 * values). The full-stack proof over the real Jdbc service and the real
 * {@code TokenRevocationService} kill rides the integration suites on
 * CI.</p>
 */
class PlatformTokenGrantServiceTest {

    private static final String PRINCIPAL = "rotation-agent@example.com";
    private static final String PASSWORD = "correct-password-123";
    private static final String PUBLIC_CLIENT_ID = "test-public-client";

    private static JWKSource<SecurityContext> jwkSource;
    private static JwtDecoder jwtDecoder;

    private final InMemoryRowStore authorizationStore = new InMemoryRowStore();
    private PlatformTokenGrantService service;

    @BeforeAll
    static void keyMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                .keyID(UUID.randomUUID().toString())
                .build();
        jwkSource = new ImmutableJWKSet<>(new JWKSet(rsaKey));
        jwtDecoder = NimbusJwtDecoder.withJwkSource(jwkSource).build();
    }

    @BeforeEach
    void setUp() {
        RegisteredClient publicClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(PUBLIC_CLIENT_ID)
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(new AuthorizationGrantType("password"))
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("https://dashboard.example.com/callback")
                .scope("openid")
                .build();
        RegisteredClientRepository clientRepository =
                new InMemoryRegisteredClientRepository(publicClient);

        AuthenticationConfiguration authenticationConfiguration =
                mock(AuthenticationConfiguration.class);
        AuthenticationManager authenticationManager = authentication -> {
            if (PRINCIPAL.equals(authentication.getName())
                    && PASSWORD.equals(authentication.getCredentials())) {
                return new UsernamePasswordAuthenticationToken(PRINCIPAL, null,
                        List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
            }
            throw new BadCredentialsException("bad credentials");
        };
        when(authenticationConfiguration.getAuthenticationManager())
                .thenReturn(authenticationManager);

        IdentitySpi identitySpi = mock(IdentitySpi.class);
        when(identitySpi.findCurrentUserAccount(any())).thenReturn(Optional.of(
                new UserAccountView(UUID.randomUUID(), PRINCIPAL, false, true,
                        Instant.now().minusSeconds(3600), Instant.now(), Set.of("CONSUMER"))));

        service = new PlatformTokenGrantService(
                provider(authenticationConfiguration),
                provider(authorizationStore),
                provider(clientRepository),
                provider(jwkSource),
                provider(AuthorizationServerSettings.builder()
                        .issuer("https://auth.example.com").build()),
                provider(jwtDecoder),
                identitySpi,
                "marketplace-api",
                PUBLIC_CLIENT_ID);
    }

    // -------------------------------------------------------------------
    // The rotation
    // -------------------------------------------------------------------

    @Test
    void rotationMintsAFreshPairWithTheSamePrincipalAndRoles() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);

        PlatformTokenGrantService.IssuedTokens rotated =
                service.refresh(granted.refreshToken());

        assertThat(rotated.refreshToken())
                .isNotBlank()
                .isNotEqualTo(granted.refreshToken());
        assertThat(rotated.accessToken()).isNotBlank();
        assertThat(rotated.csrfToken()).isNotBlank();
        assertThat(rotated.user()).isNotNull();
        assertThat(rotated.user().email()).isEqualTo(PRINCIPAL);

        // The successor access token is a REAL token the resource
        // server's own decoder validates, with the SAME subject/roles.
        var claims = jwtDecoder.decode(rotated.accessToken()).getClaims();
        assertThat(claims.get("sub")).isEqualTo(PRINCIPAL);
        assertThat(claims.get("iss")).isEqualTo("https://auth.example.com");
        @SuppressWarnings("unchecked")
        List<Object> rolesClaim = (List<Object>) claims.get("roles");
        assertThat(rolesClaim).containsExactly("CONSUMER");
    }

    @Test
    void thePresentedRefreshTokenIsDeadAfterTheRotation() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        String presented = granted.refreshToken();
        service.refresh(presented);

        // The replay: the old value is gone from the row — the official
        // row-replacement invalidation — so the second use is rejected
        // exactly like an unknown token.
        assertThatThrownBy(() -> service.refresh(presented))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    @Test
    void theSuccessorItselfRotatesExactlyOnce() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens firstRotation =
                service.refresh(granted.refreshToken());
        PlatformTokenGrantService.IssuedTokens secondRotation =
                service.refresh(firstRotation.refreshToken());

        assertThat(secondRotation.refreshToken())
                .isNotEqualTo(firstRotation.refreshToken());
        assertThatThrownBy(() -> service.refresh(firstRotation.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    // -------------------------------------------------------------------
    // The life rules (the official Token#isActive())
    // -------------------------------------------------------------------

    @Test
    void anExpiredRefreshTokenIsRejected() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        expireRefreshToken(granted.refreshToken());

        assertThatThrownBy(() -> service.refresh(granted.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    @Test
    void anInvalidatedRefreshTokenIsRejected() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        invalidateRefreshToken(granted.refreshToken());

        assertThatThrownBy(() -> service.refresh(granted.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    @Test
    void anUnknownRefreshTokenIsRejected() {
        assertThatThrownBy(() -> service.refresh("no-such-token-in-the-store"))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    // -------------------------------------------------------------------
    // The concurrency rule (one-time-use under simultaneous use)
    // -------------------------------------------------------------------

    @Test
    void twoConcurrentRotationsOfTheSameTokenAllowExactlyOne() throws Exception {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        String presented = granted.refreshToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<PlatformTokenGrantService.IssuedTokens> rotation = () -> {
            // Both threads race the SAME token from the SAME store —
            // only one may ever observe the live row.
            startTogether.await();
            return service.refresh(presented);
        };
        try {
            Future<PlatformTokenGrantService.IssuedTokens> first =
                    pool.submit(rotation);
            Future<PlatformTokenGrantService.IssuedTokens> second =
                    pool.submit(rotation);
            int successes = 0;
            String survivor = null;
            try {
                PlatformTokenGrantService.IssuedTokens won = first.get();
                successes++;
                survivor = won.refreshToken();
            } catch (java.util.concurrent.ExecutionException expected) {
                assertThat(expected.getCause()).isInstanceOf(BadCredentialsException.class);
            }
            try {
                PlatformTokenGrantService.IssuedTokens won = second.get();
                successes++;
                survivor = won.refreshToken();
            } catch (java.util.concurrent.ExecutionException expected) {
                assertThat(expected.getCause()).isInstanceOf(BadCredentialsException.class);
            }
            assertThat(successes).as("exactly one of the two concurrent rotations succeeds")
                    .isEqualTo(1);
            // And the SURVIVOR is the only live token: it rotates again,
            // the original is still dead.
            assertThat(service.refresh(survivor).refreshToken()).isNotBlank();
            assertThatThrownBy(() -> service.refresh(presented))
                    .isInstanceOf(BadCredentialsException.class);
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------
    // The deactivation rule (tokensDeactivateAll reaches the successor)
    // -------------------------------------------------------------------

    @Test
    void deactivatingAllTokensKillsTheRotatedSuccessor() {
        PlatformTokenGrantService.IssuedTokens granted = service.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens rotated =
                service.refresh(granted.refreshToken());

        // TokenRevocationService's own SQL, verbatim semantics:
        // DELETE FROM oauth2_authorization WHERE principal_name = ?
        authorizationStore.removeByPrincipal(PRINCIPAL);

        assertThatThrownBy(() -> service.refresh(rotated.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    // -------------------------------------------------------------------
    // The grant itself stays honest
    // -------------------------------------------------------------------

    @Test
    void theGrantMintsDistinctPairsAndRefusesBadCredentials() {
        PlatformTokenGrantService.IssuedTokens first = service.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens second = service.grant(PRINCIPAL, PASSWORD);

        assertThat(first.refreshToken()).isNotEqualTo(second.refreshToken());
        assertThat(first.accessToken()).isNotEqualTo(second.accessToken());
        assertThatThrownBy(() -> service.grant(PRINCIPAL, "wrong-password"))
                .isInstanceOf(BadCredentialsException.class);
    }

    // -------------------------------------------------------------------
    // Internals — the collaborators
    // -------------------------------------------------------------------

    /**
     * The OFFICIAL JdbcOAuth2AuthorizationService semantics the rotation
     * relies on, in memory: {@code save} upserts BY ROW ID (the update
     * path REPLACES the row — the new refresh token value displaces the
     * old one), {@code findByToken} matches the STORED token values.
     */
    static final class InMemoryRowStore implements OAuth2AuthorizationService {

        private final Map<String, OAuth2Authorization> rows = new ConcurrentHashMap<>();

        @Override
        public void save(OAuth2Authorization authorization) {
            rows.put(authorization.getId(), authorization);
        }

        @Override
        public void remove(OAuth2Authorization authorization) {
            rows.remove(authorization.getId());
        }

        @Override
        public OAuth2Authorization findById(String id) {
            return rows.get(id);
        }

        @Override
        public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
            for (OAuth2Authorization row : rows.values()) {
                if (tokenType == null) {
                    if (matchesAccessToken(row, token) || matchesRefreshToken(row, token)) {
                        return row;
                    }
                } else if (OAuth2TokenType.ACCESS_TOKEN.equals(tokenType)) {
                    if (matchesAccessToken(row, token)) {
                        return row;
                    }
                } else if (OAuth2TokenType.REFRESH_TOKEN.equals(tokenType)) {
                    if (matchesRefreshToken(row, token)) {
                        return row;
                    }
                }
            }
            return null;
        }

        void removeByPrincipal(String principal) {
            rows.values().removeIf(row -> row.getPrincipalName().equals(principal));
        }

        private static boolean matchesAccessToken(OAuth2Authorization row, String token) {
            return row.getAccessToken() != null
                    && row.getAccessToken().getToken().getTokenValue().equals(token);
        }

        private static boolean matchesRefreshToken(OAuth2Authorization row, String token) {
            return row.getRefreshToken() != null
                    && row.getRefreshToken().getToken().getTokenValue().equals(token);
        }
    }

    private void expireRefreshToken(String refreshToken) {
        OAuth2Authorization row = authorizationStore.findByToken(refreshToken,
                new OAuth2TokenType(OAuth2TokenType.REFRESH_TOKEN.getValue()));
        Instant now = Instant.now();
        OAuth2Authorization expired = OAuth2Authorization.from(row)
                .token(new org.springframework.security.oauth2.core.OAuth2RefreshToken(
                        refreshToken, now.minus(java.time.Duration.ofDays(20)),
                        now.minus(java.time.Duration.ofDays(19))))
                .build();
        authorizationStore.save(expired);
    }

    private void invalidateRefreshToken(String refreshToken) {
        OAuth2Authorization row = authorizationStore.findByToken(refreshToken,
                new OAuth2TokenType(OAuth2TokenType.REFRESH_TOKEN.getValue()));
        Instant now = Instant.now();
        OAuth2Authorization invalidated = OAuth2Authorization.from(row)
                .token(new org.springframework.security.oauth2.core.OAuth2RefreshToken(
                                refreshToken, now, now.plus(java.time.Duration.ofDays(14))),
                        metadata -> metadata.put(
                                OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true))
                .build();
        authorizationStore.save(invalidated);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                if (value == null) {
                    throw new IllegalStateException("no collaborator wired");
                }
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }
        };
    }
}

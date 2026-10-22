package com.marketplace.app.graphql;

import com.marketplace.identity.TokenRevocationService;
import com.marketplace.identity.spi.IdentitySpi;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Agent 1 (the Saleor 458-root campaign, PR #696 — the integrator's
 * multi-instance round): the refresh rotation's one-time-use guarantee
 * proven <b>ACROSS APPLICATION INSTANCES</b> — the exact scenario the
 * integrator's review demanded and the in-memory unit suite cannot
 * prove: <b>two INDEPENDENT grant-service instances over the SAME real
 * PostgreSQL authorization store</b> (the official {@code
 * oauth2_authorization} table, the real {@code
 * JdbcOAuth2AuthorizationService}, the real transactional
 * {@link JdbcRefreshTokenRotationStore} bean), with every leg landing
 * on the pair the way two replicas of the deployment would.
 *
 * <p><b>What is proven (the review's own acceptance list):</b></p>
 * <ol>
 *   <li><b>The concurrent double-spend across instances allows EXACTLY
 *       ONE winner</b> — both instances resolve the row and build a
 *       successor, but the atomic consumption is arbitrated by the
 *       DATABASE (one conditional {@code UPDATE ... WHERE id = ? AND
 *       refresh_token_value = ?}); the loser's verdict is zero rows,
 *       rejected exactly like a replay. No JVM lock participates — the
 *       instance-local stripe locks were removed in the same round;
 *       this test is the proof that nothing regressed with their
 *       removal.</li>
 *   <li><b>The winner's survivor is live on BOTH instances, and the
 *       presented token is dead on BOTH</b> — the consumption's
 *       outcome is visible in the shared row itself (asserted directly
 *       against {@code oauth2_authorization.refresh_token_value}).</li>
 *   <li><b>A rotation consumed on one instance is a replay on the
 *       other</b> — cross-instance continuity of the chain, the
 *       successor rotating through the OTHER service.</li>
 *   <li><b>{@code tokensDeactivateAll} (the identity owner's real
 *       {@link TokenRevocationService} kill) reaches the rotated
 *       successor on every instance.</b></li>
 * </ol>
 *
 * <p>Both service instances are constructed over the SAME real
 * collaborators the production bean uses (the context's own
 * {@link JdbcRefreshTokenRotationStore} bean included — the
 * transactional proxy, exactly as wired in production); they share NO
 * mutable Java state, exactly like two replicas of the deployment: the
 * authorization DATABASE is the only thing they share. The fixtures
 * ride the official channels (the {@code UserDetailsManager} login
 * row, the {@code RegisteredClientRepository} client row, the
 * {@code users} identity row) — nothing is invented.</p>
 *
 * <p>Local Maven runs without Docker skip this class (the house
 * {@code @Testcontainers(disabledWithoutDocker = true)} contract); the
 * GitHub Actions integration suite executes it — a pending run is
 * never reported as green.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PlatformTokenGrantRotationMultiInstanceIntegrationTest {

    private static final String PRINCIPAL = "a1-rotation-multi@example.com";
    private static final String PASSWORD = "a1-rotation-multi-password";
    private static final String CLIENT_ID = "a1-rotation-multi-client";
    private static final String AUDIENCE = "marketplace-api";
    private static final UUID USER_ROW_ID = UUID.fromString("61616161-6161-4616-8616-6161616161aa");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches the established container pattern (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private OAuth2AuthorizationService authorizationService;

    @Autowired
    private RefreshTokenRotationStore refreshTokenRotationStore;

    @Autowired
    private AuthenticationConfiguration authenticationConfiguration;

    @Autowired
    private JWKSource<SecurityContext> jwkSource;

    @Autowired
    private AuthorizationServerSettings authorizationServerSettings;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private IdentitySpi identitySpi;

    @Autowired
    private TokenRevocationService tokenRevocationService;

    /** Two INDEPENDENT grant-service instances over the same store. */
    private PlatformTokenGrantService serviceA;
    private PlatformTokenGrantService serviceB;

    @BeforeEach
    void setUp() {
        // The principal's whole footprint, deterministic per test: the
        // authorization rows, the identity row, the login row (recreated
        // through the official UserDetailsManager), the client row
        // (saved once through the official RegisteredClientRepository).
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ?", PRINCIPAL);
        jdbcTemplate.update("DELETE FROM user_role_assignments WHERE user_id = ?", USER_ROW_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", USER_ROW_ID);
        if (userDetailsManager.userExists(PRINCIPAL)) {
            userDetailsManager.deleteUser(PRINCIPAL);
        }
        UserDetails login = User.withUsername(PRINCIPAL)
                .password("{noop}" + PASSWORD)
                .roles("CONSUMER")
                .build();
        userDetailsManager.createUser(login);
        jdbcTemplate.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                """, USER_ROW_ID, PRINCIPAL, PRINCIPAL, "A1 Rotation Multi");
        if (registeredClientRepository.findByClientId(CLIENT_ID) == null) {
            RegisteredClient client = RegisteredClient.withId(UUID.randomUUID().toString())
                    .clientId(CLIENT_ID)
                    .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                    .authorizationGrantType(new AuthorizationGrantType("password"))
                    .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                    .redirectUri("https://dashboard.example.com/callback")
                    .scope("openid")
                    .build();
            registeredClientRepository.save(client);
        }
        // TWO independent instances over the SAME real collaborators —
        // the context's own transactional rotation-store bean included
        // (the production wiring; the DB is the only shared state).
        serviceA = new PlatformTokenGrantService(
                provider(authenticationConfiguration),
                provider(authorizationService),
                provider(refreshTokenRotationStore),
                provider(registeredClientRepository),
                provider(jwkSource),
                provider(authorizationServerSettings),
                provider(jwtDecoder),
                identitySpi,
                AUDIENCE,
                CLIENT_ID);
        serviceB = new PlatformTokenGrantService(
                provider(authenticationConfiguration),
                provider(authorizationService),
                provider(refreshTokenRotationStore),
                provider(registeredClientRepository),
                provider(jwkSource),
                provider(authorizationServerSettings),
                provider(jwtDecoder),
                identitySpi,
                AUDIENCE,
                CLIENT_ID);
    }

    // ---------------------------------------------------------------------
    // The review's central demand: the concurrent cross-instance
    // double-spend allows EXACTLY ONE winner — the database arbitrates.
    // ---------------------------------------------------------------------

    @Test
    void twoIndependentInstancesConcurrentlyRotatingTheSameTokenAllowExactlyOne() throws Exception {
        PlatformTokenGrantService.IssuedTokens granted = serviceA.grant(PRINCIPAL, PASSWORD);
        String presented = granted.refreshToken();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<PlatformTokenGrantService.IssuedTokens> onInstanceA = () -> {
            startTogether.await();
            return serviceA.refresh(presented);
        };
        Callable<PlatformTokenGrantService.IssuedTokens> onInstanceB = () -> {
            startTogether.await();
            return serviceB.refresh(presented);
        };
        try {
            Future<PlatformTokenGrantService.IssuedTokens> first = pool.submit(onInstanceA);
            Future<PlatformTokenGrantService.IssuedTokens> second = pool.submit(onInstanceB);
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
            assertThat(successes).as("exactly one of the two cross-instance concurrent "
                    + "rotations succeeds — the database's conditional consumption arbitrates")
                    .isEqualTo(1);

            // The winner's survivor is live through BOTH instances, and
            // the presented token is dead on BOTH (the consumption is
            // visible in the shared row itself).
            assertThat(serviceB.refresh(survivor).refreshToken()).isNotBlank();
            assertThatThrownBy(() -> serviceA.refresh(presented))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("invalid, expired or revoked");
            assertThatThrownBy(() -> serviceB.refresh(presented))
                    .isInstanceOf(BadCredentialsException.class)
                    .hasMessageContaining("invalid, expired or revoked");
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------
    // The consumption outcome lives in the shared row itself
    // ---------------------------------------------------------------------

    @Test
    void theRotationOutcomeIsVisibleInTheAuthorizationRowItself() {
        PlatformTokenGrantService.IssuedTokens granted = serviceA.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens rotated =
                serviceB.refresh(granted.refreshToken());

        String storedValue = jdbcTemplate.queryForObject(
                "SELECT refresh_token_value FROM oauth2_authorization WHERE principal_name = ?",
                String.class, PRINCIPAL);
        assertThat(storedValue)
                .as("the shared row carries the SUCCESSOR — the presented value was consumed")
                .isEqualTo(rotated.refreshToken())
                .isNotEqualTo(granted.refreshToken());
    }

    // ---------------------------------------------------------------------
    // Cross-instance continuity: consumed on A, replay on B, the
    // successor chains through the other instance
    // ---------------------------------------------------------------------

    @Test
    void aRotationConsumedOnOneInstanceIsAReplayOnTheOther() {
        PlatformTokenGrantService.IssuedTokens granted = serviceA.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens rotated =
                serviceA.refresh(granted.refreshToken());

        assertThatThrownBy(() -> serviceB.refresh(granted.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");

        PlatformTokenGrantService.IssuedTokens chained =
                serviceB.refresh(rotated.refreshToken());
        assertThat(chained.refreshToken()).isNotBlank();
        assertThat(chained.user()).isNotNull();
        assertThat(chained.user().email()).isEqualTo(PRINCIPAL);
    }

    // ---------------------------------------------------------------------
    // The deactivation kill reaches the rotated successor on EVERY
    // instance (the real TokenRevocationService, not a modeled delete)
    // ---------------------------------------------------------------------

    @Test
    void deactivatingAllTokensKillsTheRotatedSuccessorOnEveryInstance() {
        PlatformTokenGrantService.IssuedTokens granted = serviceA.grant(PRINCIPAL, PASSWORD);
        PlatformTokenGrantService.IssuedTokens rotated =
                serviceB.refresh(granted.refreshToken());

        int killed = tokenRevocationService.deactivateAllFor(
                new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of()));
        assertThat(killed).isPositive();

        assertThatThrownBy(() -> serviceA.refresh(rotated.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
        assertThatThrownBy(() -> serviceB.refresh(rotated.refreshToken()))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("invalid, expired or revoked");
    }

    // ---------------------------------------------------------------------
    // Internals
    // ---------------------------------------------------------------------

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

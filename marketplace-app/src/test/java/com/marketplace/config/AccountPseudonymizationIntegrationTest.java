package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.marketplace.MarketplaceApplication;
import com.marketplace.identity.UserService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I7 Phase 1 (account-pseudonymization-plan §5-و — the integration guard):
 * the FULL sequence on real PostgreSQL + real Redis, through real HTTP —
 * pseudonymize &rarr; login rejected &rarr; refresh rejected &rarr; the
 * old records still readable under the stable UUID (reference integrity)
 * &rarr; the cross-replica cache invalidation (the I5 two-replica pattern,
 * verbatim) &rarr; zero {@code oauth2_authorization} rows for the subject.
 *
 * <p><b>Why real Flyway (the V33 lesson):</b> the schema under test is the
 * production schema — {@code spring.flyway.enabled=true} +
 * {@code ddl-auto=none} — so V46 and the Envers {@code users_aud} mirror are
 * the real ones; a create-drop profile would rebuild the schema from the
 * entities and hide exactly the migration gaps this guard exists to catch
 * (the house pattern: {@code ExpiredAuthorizationsCleanupIntegrationTest},
 * {@code CacheInvalidationAcrossReplicasIntegrationTest}).
 *
 * <p><b>Why the PKCE login gate (the L23 pattern):</b> the only honest way to
 * prove "the login identity dies" is the real authorization-code flow on the
 * real authorization server — form login against {@code auth_users}, token
 * exchange against {@code /oauth2/token}, refresh grant with the real
 * {@code oauth2_authorization} rows. Fixtures follow the official wiring
 * ({@code RegisteredClientRepository.save}, {@code UserDetailsManager}).
 *
 * <p><b>Why replica B (the I5 pattern, verbatim):</b> the production cache is
 * {@code cache.type=redis} — ONE shared store per replica. Replica A's
 * transactional pseudonymization publishes {@code CacheInvalidationRequested}
 * and its AFTER_COMMIT {@code CacheInvalidationRelay} evicts the SHARED
 * entry; replica B's next read must miss and re-read the transformed row.
 * The tombstone probe's derivation uses the SAME bound key on both replicas
 * (the deterministic contract).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway (V1..V46), no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple' —
        // the I5 lesson: force the shared Redis so the eviction is real).
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
        // The b-2(b) secret channel for THIS guard — a test value, never a
        // production secret (secrets-policy §1: real secrets never touch Git).
        "marketplace.security.pseudonymization.subject-hmac-key=it-pseudonymization-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountPseudonymizationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    /** Replica B: a second full application context (the I5 harness shape). */
    private static ConfigurableApplicationContext replicaB;

    @BeforeAll
    static void startReplicaB() {
        // The Testcontainers extension (BeforeAllCallback) has already started
        // the shared PostgreSQL + Redis. Command-line args are the
        // highest-precedence property source (the I5 harness rationale): they
        // override the test profile's cache.type=simple / flyway disabled /
        // ddl create-drop and its localhost datasource, so replica B runs in
        // exactly the production-shaped configuration against the SAME stores.
        replicaB = new SpringApplicationBuilder(MarketplaceApplication.class)
                .profiles("test")
                .run(
                        "--server.port=0",
                        "--spring.flyway.enabled=true",
                        "--spring.jpa.hibernate.ddl-auto=none",
                        "--spring.cache.type=redis",
                        "--spring.datasource.hikari.maximum-pool-size=5",
                        "--spring.datasource.hikari.minimum-idle=1",
                        "--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--spring.data.redis.host=" + redis.getHost(),
                        "--spring.data.redis.port=" + redis.getMappedPort(6379),
                        "--marketplace.security.pseudonymization.subject-hmac-key=it-pseudonymization-key");
        assertThat(((WebServerApplicationContext) replicaB).getWebServer().getPort())
                .as("replica B must be listening on its own port").isPositive();
    }

    @org.junit.jupiter.api.AfterAll
    static void stopReplicaB() {
        if (replicaB != null) {
            replicaB.close();
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    /** Context A's identity service — the write path under the cache test. */
    @Autowired
    private UserService userServiceA;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-pseudonymization-password";
    private static final String ADMIN_USERNAME = "it-pseud-admin-user";
    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /**
     * The acceptance sequence (§5-و, one test — the phases share the state the
     * plan's sequence produces): full identity death + records stay + the
     * tombstone holds + idempotence holds.
     */
    @Test
    void fullSequence_identityDiesRecordsStayTombstoneHolds() throws Exception {
        String target = "it-pseud-target-user";
        registerUser(target, "USER");
        GateResult targetGate = loginGate(target, PASSWORD);
        assertThat(targetGate.accessToken()).isNotBlank();
        UUID targetId = syncProjectionIdViaMe(targetGate.accessToken());
        seedBookingReferenceRow(targetId);

        GateResult admin = adminGate();

        // The action — real HTTP through the administrative surface.
        HttpResponse<String> pseudonymize = postJsonWithBearer(
                "/api/v1/admin/users/" + targetId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"data-subject erasure request\"}");
        assertThat(pseudonymize.statusCode())
                .as("pseudonymize call: %s", body(pseudonymize)).isEqualTo(200);

        // §5-أ step 3/4 — the row's direct identifiers are gone, the status
        // column is stamped, the UUID is untouched.
        var row = jdbcTemplate.queryForMap(
                "SELECT subject, email, display_name, pseudonymized_at FROM users WHERE id = ?", targetId);
        String derivedSubject = (String) row.get("subject");
        // "anon-" (5) + the full 64-char hex fingerprint = 69 — the plan's
        // corrected arithmetic (the original 5+1+64=70 double-counted the
        // hyphen; pinned by SubjectPseudonymizerTest too).
        assertThat(derivedSubject).startsWith("anon-").hasSize(69);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("display_name")).isNull();
        assertThat(row.get("pseudonymized_at")).isNotNull();

        // The deterministic contract: the replacement equals the HMAC the
        // same key derives for the original subject (the tombstone probe's
        // own arithmetic — verified against the JDK independently).
        assertThat(derivedSubject).isEqualTo(deriveWithJdkHmac(target));

        // §5-أ step 5 — the login identity rows are gone (both tables).
        assertThat(userDetailsManager.userExists(target)).isFalse();
        Integer authRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_users WHERE username = ?", Integer.class, target);
        assertThat(authRows).isZero();
        Integer authorityRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_authorities WHERE username = ?", Integer.class, target);
        assertThat(authorityRows).isZero();

        // §5-أ step 6 — zero authorization rows for the original principal.
        Integer authorizationRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                Integer.class, target);
        assertThat(authorizationRows).isZero();

        // The refresh grant dies with the authorization rows (invalid_grant).
        HttpResponse<String> refreshAfter = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + targetGate.refreshToken());
        assertThat(refreshAfter.statusCode())
                .as("refresh after pseudonymization: %s", body(refreshAfter)).isEqualTo(400);

        // The form login dies with the auth_users row (no identity to
        // authenticate against — a deleted row, not a disabled one).
        assertThat(loginIsRejected(target, PASSWORD))
                .as("the next login attempt must be rejected").isTrue();

        // The Envers mirror wrote the revision with the V46 column (V33
        // lesson guard): the audit history keeps flowing post-V46.
        Integer audRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_aud WHERE id = ? AND subject = ?",
                Integer.class, targetId, derivedSubject);
        assertThat(audRows).as("users_aud must carry the pseudonymized revision").isPositive();

        // Records stay: the booking still references the stable UUID and is
        // readable through the administrative surface (reference integrity —
        // Art. 17(3)(b)/20(4), the plan's §4 refusal of hard deletion).
        // Authenticated read (the surface's own gate) with the admin token.
        HttpResponse<String> bookings = getWithBearer(
                "/api/v1/admin/bookings?status=PENDING&size=50", admin.accessToken());
        assertThat(bookings.statusCode()).as("admin bookings read: %s", body(bookings)).isEqualTo(200);
        assertThat(bookings.body()).contains(targetId.toString());

        // §5-أ step 8 — the tombstone: the still-valid access token (its 900s
        // TTL window) cannot resurrect the identity through /me provisioning.
        HttpResponse<String> meAfter = getWithBearer("/api/v1/users/me", targetGate.accessToken());
        assertThat(meAfter.statusCode())
                .as("/me after pseudonymization (tombstone): %s", body(meAfter)).isEqualTo(409);

        // §5-أ step 2 — idempotence: the second call is a no-op (200, no
        // change — the same derived subject, no error).
        HttpResponse<String> again = postJsonWithBearer(
                "/api/v1/admin/users/" + targetId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"re-run\"}");
        assertThat(again.statusCode()).as("idempotent re-run: %s", body(again)).isEqualTo(200);
        String subjectAfterRerun = jdbcTemplate.queryForObject(
                "SELECT subject FROM users WHERE id = ?", String.class, targetId);
        assertThat(subjectAfterRerun).isEqualTo(derivedSubject);
    }

    /**
     * §5-و negative — a non-admin token cannot pseudonymize anyone (403).
     */
    @Test
    void consumerTokenCannotPseudonymize() throws Exception {
        String consumer = "it-pseud-consumer-user";
        registerUser(consumer, "USER");
        GateResult consumerGate = loginGate(consumer, PASSWORD);
        UUID someId = syncProjectionIdViaMe(consumerGate.accessToken());

        HttpResponse<String> attempt = postJsonWithBearer(
                "/api/v1/admin/users/" + someId + "/pseudonymize", consumerGate.accessToken(),
                "{\"reason\":\"must be rejected\"}");
        assertThat(attempt.statusCode())
                .as("consumer attempt: %s", body(attempt)).isEqualTo(403);
    }

    /**
     * L23's counting constraint, reused verbatim — the last active ADMIN is
     * untouchable (409 before any mutation). The class registers exactly ONE
     * admin (the shared gate admin) so the count is 1 once the Flyway-seeded
     * admin is silenced (the CI adaptation documented on the L23 gate test).
     */
    @Test
    void lastActiveAdminCannotBePseudonymized() throws Exception {
        GateResult adminGate = adminGate();
        UUID adminId = syncProjectionIdViaMe(adminGate.accessToken());

        UserDetails seededAdmin = silenceSeededAdminIfPresent();
        try {
            HttpResponse<String> attempt = postJsonWithBearer(
                    "/api/v1/admin/users/" + adminId + "/pseudonymize", adminGate.accessToken(),
                    "{\"reason\":\"must not succeed\"}");

            assertThat(attempt.statusCode())
                    .as("last-admin attempt: %s", body(attempt)).isEqualTo(409);
            assertThat(attempt.body()).contains("last active ADMIN");
        } finally {
            restoreSeededAdmin(seededAdmin);
        }
        // Nothing changed: the admin still authenticates.
        assertThat(loginIsRejected(ADMIN_USERNAME, PASSWORD)).isFalse();
    }

    /**
     * §5-و — the cross-replica cache invalidation (the I5 pattern, verbatim):
     * replica B's warmed shared entry (the pre-pseudonymization row) must turn
     * fresh after replica A's transactional pseudonymization evicts it through
     * the AFTER_COMMIT relay. Only a miss-and-reload can produce the
     * transformed subject on B.
     */
    @Test
    void cacheInvalidationOnReplicaA_propagatesToReplicaB() {
        // A dedicated user: auth row through the manager, projection row
        // through SQL (the I5 seed pattern — no login needed for the cache
        // proof, which exercises the read path, not the gate).
        String cacheUser = "it-pseud-cache-user";
        registerUser(cacheUser, "USER");
        UUID cacheUserId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """,
                cacheUserId, cacheUser, "cache@b.com", "Cache User");
        try {
            // Replica B warms the SHARED entry (the users cache) with the
            // pre-pseudonymization row.
            UserService replicaBUsers = replicaB.getBean(UserService.class);
            var before = replicaBUsers.getById(cacheUserId);
            assertThat(before.getSubject()).isEqualTo(cacheUser);

            // Replica A runs the real transactional operation (service path:
            // transform + AFTER_COMMIT eviction in A's JVM).
            userServiceA.pseudonymizeAccount(cacheUserId, "cross-replica cache proof", "admin-actor");

            // Replica B must see the transformed subject — only a shared-store
            // eviction (A's relay) followed by a miss-and-reload produces it.
            awaitTransformedSubjectOnReplicaB(replicaBUsers, cacheUserId);
        } finally {
            // The row stays pseudonymized (the action is one-way) — this test
            // only cleans its own fixtures where the operation allows it.
        }
    }

    private void awaitTransformedSubjectOnReplicaB(UserService replicaBUsers, UUID userId) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        String last = null;
        while (System.nanoTime() < deadline) {
            last = replicaBUsers.getById(userId).getSubject();
            if (last != null && last.startsWith("anon-")) {
                return;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting replica B's fresh read", ex);
            }
        }
        throw new AssertionError(String.format(
                "Replica B never saw the post-eviction transformed subject (last seen: %s) — "
                        + "the invalidation originating on replica A did not propagate through "
                        + "the shared Redis cache.", last));
    }

    // -- fixtures & helpers (the L23 gate shapes) --------------------------

    private void registerUser(String username, String role) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(role)
                .build());
    }

    private void registerLoginGateClient() {
        if (registeredClientRepository.findByClientId(CLIENT_ID) != null) {
            return;
        }
        RegisteredClient loginGateClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}" + CLIENT_SECRET)
                .clientName("Pseudonymization Gate Integration Test Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        registeredClientRepository.save(loginGateClient);
    }

    /**
     * The real browser-less login gate — the L23 gate's proven five-step
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login with
     * the session; (2) the login form's CSRF token (bound to the session);
     * (3) credentials POST &rarr; 302 to the SAVED authorization request;
     * (4) re-issuing the authorize request as the authenticated principal
     * &rarr; 302 to the client with the code; (5) the token exchange
     * (client_secret_basic + the PKCE verifier).
     */
    private GateResult loginGate(String username, String password) throws Exception {
        registerLoginGateClient();
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        // (1) Unauthenticated authorization request -> redirect to the login page.
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        // (2) Fetch the login form; the CSRF token is bound to the session.
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        // (3) Submit credentials -> redirect back to the saved authorization request.
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        // (4) Re-issue the authorization request as an authenticated principal -> code.
        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        String authorizationCode = queryParam(redirect, "code");

        // (5) Exchange the code for tokens (client_secret_basic + PKCE verifier).
        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    /** PKCE verifier per the official RFC 7636 flow shape (login-gate test). */
    private static String randomCodeVerifier() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }
    private GateResult adminGate() throws Exception {
        registerUser(ADMIN_USERNAME, "ADMIN");
        return loginGate(ADMIN_USERNAME, PASSWORD);
    }

    private UUID syncProjectionIdViaMe(String accessToken) throws Exception {
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("/me sync: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        assertThat(user.path("id").asString()).as("the /me response must carry the id").isNotBlank();
        return UUID.fromString(user.path("id").asString());
    }

    /**
     * Seeds the booking reference row (V3): users row for the provider (FK
     * parent), provider_listings row, then the booking whose consumer is the
     * pseudonymization target — the reference-integrity fixture.
     */
    private void seedBookingReferenceRow(UUID consumerId) {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                providerId, "it-pseud-provider@example.com",
                "it-pseud-provider@example.com", "I7 Reference Provider");
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, providerId, "I7 Reference Listing",
                "Reference-integrity proof listing", "home", 100_00L);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency)
                VALUES (?, ?, ?, ?, 'PENDING', 100_00, 'SAR')
                ON CONFLICT (id) DO NOTHING
                """,
                bookingId, consumerId, providerId, listingId);
    }

    private UserDetails silenceSeededAdminIfPresent() {
        UserDetails seeded;
        try {
            seeded = userDetailsManager.loadUserByUsername("admin");
        } catch (UsernameNotFoundException noSeed) {
            return null;
        }
        if (seeded.isEnabled()) {
            userDetailsManager.updateUser(withEnabled(seeded, false));
        }
        return seeded;
    }

    private void restoreSeededAdmin(UserDetails seeded) {
        if (seeded != null && seeded.isEnabled()) {
            userDetailsManager.updateUser(withEnabled(seeded, true));
        }
    }

    private static UserDetails withEnabled(UserDetails source, boolean enabled) {
        return org.springframework.security.core.userdetails.User.withUsername(source.getUsername())
                .password(source.getPassword())
                .authorities(source.getAuthorities())
                .accountExpired(!source.isAccountNonExpired())
                .accountLocked(!source.isAccountNonLocked())
                .credentialsExpired(!source.isCredentialsNonExpired())
                .disabled(!enabled)
                .build();
    }

    /** The b-2(b) arithmetic recomputed with the JDK — the byte-level truth. */
    private static String deriveWithJdkHmac(String subject) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    "it-pseudonymization-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] fingerprint = mac.doFinal(subject.getBytes(StandardCharsets.UTF_8));
            return "anon-" + java.util.HexFormat.of().formatHex(fingerprint);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private boolean loginIsRejected(String username, String password) throws Exception {
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(randomCodeVerifier()))
                + "&code_challenge_method=S256";
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode())
                .as("login POST must answer the 302 redirect contract: %s", body(loginPost))
                .isEqualTo(302);
        String location = loginPost.headers().firstValue("Location").orElse("");
        return location.contains("/login?error");
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }


    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithBearer(String path, String accessToken, String json)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + portA;
    }

    private String sessionCookie(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            Matcher matcher = SESSION_COOKIE.matcher(header);
            if (matcher.find()) {
                return matcher.group(1) + "=" + matcher.group(2);
            }
        }
        return null;
    }

    private String latestSessionCookie(HttpResponse<?> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie != null ? cookie : fallback;
    }

    private String csrfTokenFrom(String loginHtml) {
        Matcher matcher = CSRF_INPUT.matcher(loginHtml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = CSRF_INPUT_REVERSED.matcher(loginHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String queryParam(String location, String name) {
        String query = location.substring(location.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(500, response.body().length()));
    }

    private record GateResult(String accessToken, String refreshToken, String tokenType, String idToken) {
    }
}

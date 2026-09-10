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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-4 — the audit
 * history purge): the integration guard on real PostgreSQL + real Redis,
 * through real HTTP. The plan's purge option, verbatim: "حذف صفوف
 * {@code users_aud} للمستخدم (WHERE id=…) + كنس {@code created_by}/
 * {@code updated_by} عبر نحو 20 جدولاً — عملية ثقيلة تُشغَّل خارج
 * المعاملة، تقبل التدرّج".
 *
 * <p><b>Why real Flyway (the V33 lesson, the Phase 1 guard's own
 * rationale):</b> the schema under test is the production schema — the
 * audit columns' actual presence per table (V24 mirrors carry them for
 * every entity; the base {@code disputes} table does NOT — V20) is what
 * the information_schema discovery measures; a create-drop profile
 * would rebuild a different truth.
 *
 * <p><b>Why the real Envers path for the mirror history:</b> the
 * load-bearing ORDER (closure recovery BEFORE the deletion) is only
 * honest when the mirror rows are the real ones — the /me sync writes
 * the ADD revision (subject = the raw subject), the pseudonymization
 * writes the MOD revision (subject = the derived replacement). Seeded
 * audit cells on a booking row and a mirror row prove the scrub's reach
 * and the counterparty's isolation.
 *
 * <p><b>Why the PKCE login gate (the L23 pattern):</b> the purge surface
 * is administrative — the guard proves the real authorization path: a
 * consumer token is denied (403), a live account is rejected (409), the
 * happy path runs with a real ADMIN bearer.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway (V1..V46), no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the I5 lesson — force the shared Redis).
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
class AuditHistoryPurgeIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-aud-purge-password";
    private static final String ADMIN_USERNAME = "it-aud-admin-user";
    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String COUNTERPARTY_AUDITOR = "it-aud-counterparty-subject";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /**
     * The acceptance sequence (one test — the phases share the state the
     * sequence produces): pseudonymize &rarr; purge &rarr; the target's
     * audit identity dies in every audit cell the schema reports &rarr;
     * the counterparty's cells survive &rarr; his mirror history is gone
     * &rarr; the re-run answers zero on both counts.
     */
    @Test
    void fullSequence_auditIdentityDiesCounterpartyStaysMirrorGone() throws Exception {
        // The raw subject IS the login username (the L23 gate: the JWT sub
        // = the authenticated principal's name; the /me sync stores it).
        String target = "it-aud-target-user";
        registerUser(target, "USER");
        GateResult targetGate = loginGate(target, PASSWORD);
        assertThat(targetGate.accessToken()).isNotBlank();
        UUID subjectId = syncProjectionIdViaMe(targetGate.accessToken());

        // The real Envers ADD revision exists (subject = the raw subject):
        // the load-bearing input of the closure recovery.
        Integer audBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_aud WHERE id = ?", Integer.class, subjectId);
        assertThat(audBefore).as("the real sync must have written the mirror ADD revision").isPositive();

        // Seeded audit cells: the target's own cells on a booking row (both
        // columns), the counterparty's cells on another booking row, and a
        // mirror row (reviews_aud carries the audit columns — V24 §8).
        UUID providerId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                providerId, "it-aud-provider@example.com",
                "it-aud-provider@example.com", "I7 Audit Purge Provider");
        UUID listingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, providerId, "I7 Audit Purge Listing", "guard listing", "home", 100_00L);
        UUID targetBookingId = UUID.randomUUID();
        UUID counterpartyBookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'PENDING', 100_00, 'SAR', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                targetBookingId, subjectId, providerId, listingId, target, target);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, created_by, updated_by)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 100_00, 'SAR', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyBookingId, providerId, providerId, listingId,
                COUNTERPARTY_AUDITOR, COUNTERPARTY_AUDITOR);
        int rev = 9_200_000 + (int) (System.currentTimeMillis() % 100_000);
        jdbcTemplate.update("INSERT INTO revinfo (rev, revtstmp) VALUES (?, ?)", rev, System.currentTimeMillis());
        UUID reviewMirrorId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reviews_aud (id, rev, revtype, reviewer_id, provider_id, rating, comment, created_by, updated_by) "
                        + "VALUES (?, ?, 0, ?, ?, 5, 'mirror audit cell', ?, ?)",
                reviewMirrorId, rev, subjectId, providerId, target, target);

        GateResult admin = adminGate();

        // The purge's own guard: it completes an erasure flow — Phase 1's
        // surface must run first (the real HTTP path).
        HttpResponse<String> pseudonymize = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"data-subject erasure request\"}");
        assertThat(pseudonymize.statusCode())
                .as("pseudonymize call: %s", body(pseudonymize)).isEqualTo(200);

        // The action — real HTTP through the administrative surface.
        HttpResponse<String> purge = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/purge-audit-history", admin.accessToken(),
                "{\"reason\":\"data-subject erasure request\"}");
        assertThat(purge.statusCode())
                .as("purge call: %s", body(purge)).isEqualTo(200);
        JsonNode purgeBody = objectMapper.readTree(purge.body());
        int scrubbed = purgeBody.path("scrubbedRows").asInt();
        int deleted = purgeBody.path("usersAudRowsDeleted").asInt();
        // The measured floor: the target's booking (created_by + updated_by)
        // + the mirror row (created_by + updated_by) + his own users row's
        // created_by (the real sync wrote it) — every cell the closure
        // touches, counted per statement.
        assertThat(scrubbed).as("the scrub's exact floor (target booking 2 + mirror 2 + users 1)")
                .isGreaterThanOrEqualTo(5);
        // The real Envers path wrote the ADD revision + the
        // pseudonymization MOD revision.
        assertThat(deleted).as("the mirror deletion's floor (ADD + MOD revisions)")
                .isGreaterThanOrEqualTo(2);

        // The target's audit identity died in the seeded cells...
        assertThat(jdbcTemplate.queryForMap(
                "SELECT created_by, updated_by FROM bookings WHERE id = ?", targetBookingId))
                .as("the target's booking audit cells die (both columns)")
                .containsEntry("created_by", null)
                .containsEntry("updated_by", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT created_by, updated_by FROM reviews_aud WHERE id = ? AND rev = ?",
                reviewMirrorId, rev))
                .as("the mirror row's audit cells die too (the plan's P5: created_by in EVERY table)")
                .containsEntry("created_by", null)
                .containsEntry("updated_by", null);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by FROM users WHERE id = ?", String.class, subjectId))
                .as("his own row's auditor trail dies").isNull();

        // ...the counterparty's cells survive (the isolation proof).
        assertThat(jdbcTemplate.queryForMap(
                "SELECT created_by, updated_by FROM bookings WHERE id = ?", counterpartyBookingId))
                .as("the counterparty's audit cells survive untouched")
                .containsEntry("created_by", COUNTERPARTY_AUDITOR)
                .containsEntry("updated_by", COUNTERPARTY_AUDITOR);
        // The admin's own audit identity (the pseudonymization MOD's
        // updated_by) never matched the closure — and that row is gone
        // with the deletion anyway.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_aud WHERE id = ?", Integer.class, subjectId))
                .as("the target's mirror history is gone wholesale (the plan's letter)")
                .isZero();
        Integer counterpartyAud = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_aud WHERE id = ?", Integer.class, providerId);
        assertThat(counterpartyAud).as("other users' mirror history is untouched (zero here — "
                + "the provider row was seeded raw, never through the entity path)").isZero();

        // Idempotence: the re-run answers zero on both counts — the
        // closure is the current subject alone (the mirror history is
        // gone) and NULL matches nothing.
        HttpResponse<String> again = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/purge-audit-history", admin.accessToken(),
                "{\"reason\":\"re-run\"}");
        assertThat(again.statusCode()).as("idempotent re-run: %s", body(again)).isEqualTo(200);
        JsonNode againBody = objectMapper.readTree(again.body());
        assertThat(againBody.path("scrubbedRows").asInt()).isZero();
        assertThat(againBody.path("usersAudRowsDeleted").asInt()).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT created_by, updated_by FROM bookings WHERE id = ?", counterpartyBookingId)
                .get("created_by")).as("the re-run never touches the counterparty").isEqualTo(COUNTERPARTY_AUDITOR);
    }

    /**
     * The guard's negative: a LIVE account is rejected with 409 before any
     * statement runs (the audit purge completes an erasure flow —
     * pseudonymize first). Also the unknown-id mapping (404) on the same
     * surface.
     */
    @Test
    void liveAccountIsRejectedBeforeAnyStatementRuns() throws Exception {
        String live = "it-aud-live-user";
        registerUser(live, "USER");
        GateResult liveGate = loginGate(live, PASSWORD);
        UUID liveId = syncProjectionIdViaMe(liveGate.accessToken());
        GateResult admin = adminGate();

        HttpResponse<String> attempt = postJsonWithBearer(
                "/api/v1/admin/users/" + liveId + "/purge-audit-history", admin.accessToken(),
                "{\"reason\":\"must be rejected\"}");
        assertThat(attempt.statusCode())
                .as("live account attempt: %s", body(attempt)).isEqualTo(409);
        assertThat(attempt.body()).contains("pseudonymized");

        HttpResponse<String> unknown = postJsonWithBearer(
                "/api/v1/admin/users/" + UUID.randomUUID() + "/purge-audit-history",
                admin.accessToken(), "{\"reason\":\"unknown id\"}");
        assertThat(unknown.statusCode())
                .as("unknown id attempt: %s", body(unknown)).isEqualTo(404);
    }

    /**
     * The guard's negative: a non-admin token cannot purge anyone (403).
     */
    @Test
    void consumerTokenCannotPurge() throws Exception {
        String consumer = "it-aud-consumer-user";
        registerUser(consumer, "USER");
        GateResult consumerGate = loginGate(consumer, PASSWORD);
        UUID someId = syncProjectionIdViaMe(consumerGate.accessToken());

        HttpResponse<String> attempt = postJsonWithBearer(
                "/api/v1/admin/users/" + someId + "/purge-audit-history", consumerGate.accessToken(),
                "{\"reason\":\"must be rejected\"}");
        assertThat(attempt.statusCode())
                .as("consumer attempt: %s", body(attempt)).isEqualTo(403);
    }

    // -- fixtures & helpers (the Phase 1 guard's shapes, verbatim) -------

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
                .clientName("Audit History Purge Gate Integration Test Client")
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
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login
     * with the session; (2) the login form's CSRF token; (3) credentials
     * POST &rarr; 302 to the saved authorization request; (4) re-issuing
     * the authorize request as the authenticated principal &rarr; 302 to
     * the client with the code; (5) the token exchange.
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

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode())
                .as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        String authorizationCode = queryParam(redirect, "code");

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

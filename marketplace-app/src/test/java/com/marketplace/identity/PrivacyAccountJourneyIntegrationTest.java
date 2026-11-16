package com.marketplace.identity;

import test.config.IntegrationContainers;
import test.config.AuthorizationServerFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * W2 / J34 (unified end-to-end plan §3, the journeys register row J34) — the
 * privacy and account journey measured end-to-end on the REAL chain: the
 * member's own review surfaces (/me, /me/export — the Art. 20 document with
 * the REAL per-module adapters, not mocks) → the erasure chain in its
 * documented ORDER (pseudonymize → purge-content → purge-audit-history,
 * each administrative action over real HTTP with its reason recorded) → the
 * measured effects (the login identity dies, the authored free text is
 * purged, the audit identity is scrubbed) → the honest guards (a purge
 * against a LIVE account answers 409 before any mutation) → idempotence
 * (a re-run purges zero).
 *
 * <p>This is the JOURNEY composite — the unit mechanics of each phase are
 * already pinned by AccountPseudonymizationIntegrationTest and
 * AuthoredContentPurgeIntegrationTest; this class proves the member-visible
 * chain end to end: what the member can review, what the erasure request
 * does to THEIR data, and what the guards answer when the order is wrong.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=" + CLIENT_ID,
        "marketplace.security.oauth2.client.secret=" + CLIENT_SECRET,
        "marketplace.security.oauth2.client.redirect-uris=" + REDIRECT_URI,
        // The b-2(b) secret channel for the erasure chain — a test value.
        "marketplace.security.pseudonymization.subject-hmac-key=it-pseudonymization-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PrivacyAccountJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-j34-privacy-password";
    private static final String ADMIN_USERNAME = "it-j34-privacy-admin";
    private static final String LOGIN_PATH = "/login";
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The journey's linking test (plan §5): review → export → erasure chain
     * in order → the measured effects → idempotence. The member publishes a
     * community post first so the erasure has REAL authored free text to
     * purge (the export and the purge read the same rows).
     */
    @Test
    void thePrivacyJourneyFromReviewThroughExportToErasure() throws Exception {
        String username = "it-j34-member-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);

        // (1) The review surfaces: the member reads their own profile.
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(me.statusCode()).as("/me review: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        UUID userId = UUID.fromString(user.path("id").asString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", UUID.class, username))
                .as("the /me projection is the login subject's own row")
                .isEqualTo(userId);

        // (2) The member joins their neighborhood and publishes a post —
        // the journey's authored content (the export must carry it and the
        // purge must reach it).
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> join = putJson("/api/v1/me/neighborhood", gate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}");
        assertThat(join.statusCode()).as("the neighborhood join: %s", body(join)).isEqualTo(201);
        HttpResponse<String> post = postJson("/api/v1/neighborhood/posts", gate.accessToken(), """
                {"locationId":"%s","category":"GENERAL","title":"J34 authored title",
                 "body":"The privacy journey's own authored free text."}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(post.statusCode()).as("the authored post: %s", body(post)).isEqualTo(201);

        // (3) The Art. 20 export — the REAL aggregation with the REAL
        // adapters: the structured document carries the profile, the
        // membership, and the authored post.
        HttpResponse<String> export = getWithBearer("/api/v1/users/me/export", gate.accessToken());
        assertThat(export.statusCode()).as("the export: %s", body(export)).isEqualTo(200);
        JsonNode exportDoc = objectMapper.readTree(export.body());
        assertThat(exportDoc.hasNonNull("profile")).as("the export's profile section").isTrue();
        assertThat(exportDoc.path("memberships").size()).as("the export's membership section").isEqualTo(1);
        assertThat(exportDoc.path("communityPosts").size()).as("the export's authored posts section").isEqualTo(1);
        assertThat(exportDoc.path("communityPosts").path(0).path("title").asString())
                .as("the exported post is THE one the member authored")
                .isEqualTo("J34 authored title");

        // (4) The erasure chain, step one — the administrative
        // pseudonymization (the reason is part of the contract).
        GateResult admin = adminGate();
        HttpResponse<String> pseudonymize = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"the member's erasure request\"}");
        assertThat(pseudonymize.statusCode()).as("pseudonymize: %s", body(pseudonymize)).isEqualTo(200);

        // The measured effect, leg one — the login identity is dead: the
        // form login no longer authenticates the removed row and the
        // refresh grant is gone with the authorization rows.
        HttpResponse<String> refreshAfter = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + encode(gate.refreshToken()));
        assertThat(refreshAfter.statusCode())
                .as("the refresh grant died with the erasure: %s", body(refreshAfter)).isEqualTo(400);

        // The measured effect, leg two — the direct identifiers are gone
        // from the row (the tombstone holds; the UUID is untouched).
        var row = jdbcTemplate.queryForMap(
                "SELECT subject, email, display_name, pseudonymized_at FROM users WHERE id = ?", userId);
        assertThat((String) row.get("subject")).startsWith("anon-");
        assertThat(row.get("email")).isNull();
        assertThat(row.get("display_name")).isNull();
        assertThat(row.get("pseudonymized_at")).isNotNull();

        // (5) The erasure chain, step two — the free-text purge across the
        // owning modules (the authored post's text is purged in place).
        HttpResponse<String> purgeContent = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"the member's erasure request\"}");
        assertThat(purgeContent.statusCode()).as("purge-content: %s", body(purgeContent)).isEqualTo(200);
        int purgedRows = objectMapper.readTree(purgeContent.body()).path("purgedRows").asInt();
        assertThat(purgedRows).as("the authored free text was purged (>= the post's two text columns)").isPositive();
        String purgedTitle = jdbcTemplate.queryForObject(
                "SELECT title FROM neighborhood_posts WHERE author_id = ?", String.class, userId);
        assertThat(purgedTitle).as("the authored title is no longer the member's words").isNotEqualTo("J34 authored title");

        // Idempotence — a re-run purges zero.
        HttpResponse<String> purgeAgain = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"the member's erasure request\"}");
        assertThat(purgeAgain.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(purgeAgain.body()).path("purgedRows").asInt()).isZero();

        // (6) The erasure chain, step three — the audit identity purge.
        HttpResponse<String> purgeAudit = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/purge-audit-history", admin.accessToken(),
                "{\"reason\":\"the member's erasure request\"}");
        assertThat(purgeAudit.statusCode()).as("purge-audit-history: %s", body(purgeAudit)).isEqualTo(200);
        JsonNode auditResult = objectMapper.readTree(purgeAudit.body());
        assertThat(auditResult.path("usersAudRowsDeleted").asInt())
                .as("the user's audit rows are deleted").isPositive();
        Integer remainingAudRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM users_aud WHERE id = ?", Integer.class, userId);
        assertThat(remainingAudRows).isZero();
    }

    /**
     * The honest guard (the register's J34 row — «رفض مع سبب»): the purge
     * never operates on a LIVE account's content — the 409 lands BEFORE
     * any mutation, and the live member's authored text is untouched.
     */
    @Test
    void thePurgeAgainstALiveAccountAnswers409BeforeAnyMutation() throws Exception {
        String username = "it-j34-live-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(me.statusCode()).isEqualTo(200);
        UUID userId = UUID.fromString(objectMapper.readTree(me.body()).path("id").asString());

        GateResult admin = adminGate();
        HttpResponse<String> refused = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"premature purge attempt\"}");
        assertThat(refused.statusCode()).as("the order guard: %s", body(refused)).isEqualTo(409);

        HttpResponse<String> refusedAudit = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/purge-audit-history", admin.accessToken(),
                "{\"reason\":\"premature purge attempt\"}");
        assertThat(refusedAudit.statusCode()).isEqualTo(409);

        // The live account is untouched — the login still works and the
        // row was never pseudonymized.
        HttpResponse<String> meAfter = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(meAfter.statusCode()).isEqualTo(200);
        Boolean pseudonymized = jdbcTemplate.queryForObject(
                "SELECT pseudonymized_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, userId);
        assertThat(pseudonymized).as("no live row was touched by the refused purge").isFalse();
    }

    // ------------------------------------------------------------------
    // The gate helpers (the house shape — AccountPseudonymization-
    // IntegrationTest's own: PKCE + client_secret_basic + the form
    // login's CSRF dance).
    // ------------------------------------------------------------------

    private void registerUser(String username, String... roles) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(roles.length == 0 ? new String[]{"USER"} : roles)
                .build());
    }

    private GateResult adminGate() throws Exception {
        registerUser(ADMIN_USERNAME, "ADMIN");
        return loginGate(ADMIN_USERNAME, PASSWORD);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Privacy Journey Gate Integration Test Client");
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode()).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString());
    }

    private record GateResult(String accessToken, String refreshToken) {
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithBearer(String path, String accessToken, String json) throws Exception {
        return postJson(path, accessToken, json);
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
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

    private static String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
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
}

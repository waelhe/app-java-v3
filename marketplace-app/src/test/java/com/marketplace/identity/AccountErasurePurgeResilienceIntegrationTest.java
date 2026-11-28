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
 * G3/833-01 (the 2026-11-28 review's P2) — the account-erasure purge's own
 * failure law, measured end-to-end on the real chain: the durable,
 * asynchronous {@code AccountIdentityErased} publication runs the twelve
 * authored-content adapters and b-4, and one adapter's failure — here the
 * media leg, whose tier-2 object-storage channel is unconfigured in this
 * profile — must be <b>isolated, durable and honest</b>:
 *
 * <ol>
 *   <li><b>Isolated:</b> the later adapters still run (the subject's review
 *       comment is purged) and b-4 still runs (the users_aud mirror is
 *       gone).</li>
 *   <li><b>Durable:</b> the media rows hold the retryable
 *       {@code PURGE_PENDING} tombstone, and the event publication registry
 *       keeps an open row for the purger listener — the record the
 *       {@code EventPublicationResubmission} sweep resubmits.</li>
 *   <li><b>Honest:</b> the member's pseudonymization/erasure request answers
 *       200 — the identity erasure itself succeeded; the purge runs on the
 *       publication, not the request thread (the pre-fix synchronous
 *       AFTER_COMMIT listener propagated the storage failure into a 5xx
 *       <em>after</em> the commit, leaving an erased subject with a failed
 *       response and no retry surface but the administrator).</li>
 * </ol>
 *
 * <p>The storage outage is the review's own trigger variant — "the storage
 * channel is unconfigured and the subject owns media": the test profile
 * leaves {@code MEDIA_S3_*} blank, so no {@code S3MediaStorage} bean exists
 * and {@code MediaService.destroyObjectsForErasure} answers
 * {@code ServiceUnavailableException} deterministically. The seeded
 * authored review is the isolation witness: the reviews adapter runs after
 * the media adapter in the orchestration's injection order.</p>
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
class AccountErasurePurgeResilienceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-j48-resilience-password";
    private static final String ADMIN_USERNAME = "it-j48-resilience-admin";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORED_REVIEW_COMMENT = "J48 authored review text";

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

    @Test
    void aStorageOutageInTheMediaLegIsIsolatedDurableAndHonest() throws Exception {
        String username = "it-j48-member-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(me.statusCode()).as("/me review: %s", body(me)).isEqualTo(200);
        UUID userId = UUID.fromString(objectMapper.readTree(me.body()).path("id").asString());

        // The subject owns authored content across the witness pair: a
        // review (the reviews adapter runs AFTER the media adapter) and
        // media (the failing leg — its tier-2 channel is unconfigured here).
        UUID reviewId = seedReviewAndReviewMedia(userId);
        UUID mediaAssetId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, listing_id, provider_id, object_key, content_type, "
                        + "size_bytes, status, position) VALUES (?, ?, ?, ?, 'image/jpeg', 101, 'UPLOADED', 1)",
                mediaAssetId, UUID.randomUUID(), userId, "erasure/j48-outage-asset.jpg");

        GateResult admin = adminGate();
        HttpResponse<String> pseudonymize = postJsonWithBearer(
                "/api/v1/admin/users/" + userId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"the member's erasure request\"}");

        // Honest (the pre-fix defect): the erasure request answers 200 —
        // the identity erasure committed and the purge runs on the durable
        // publication, never on the request thread. The old synchronous
        // AFTER_COMMIT listener propagated the media failure into a 5xx
        // AFTER the commit.
        assertThat(pseudonymize.statusCode())
                .as("pseudonymize with a failing purge leg must still answer 200: %s", body(pseudonymize))
                .isEqualTo(200);

        // Isolated — the bounded poll (30s / 200ms, the house shape) waits
        // for b-4's own effect: the audit mirror's death is the LATEST
        // witness of the failing delivery's catch path (the content loop
        // runs before b-4, so by the time users_aud is empty the reviews
        // adapter's purge below is deterministic too). On the pre-fix code
        // the poll times out and the assertion names the defect.
        long deadline = System.nanoTime() + 30_000_000_000L;
        Integer usersAudRows = 1;
        while (System.nanoTime() < deadline) {
            usersAudRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM users_aud WHERE id = ?", Integer.class, userId);
            if (usersAudRows != null && usersAudRows == 0) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(usersAudRows)
                .as("b-4 (the audit-history purge) still ran after the incomplete b-3 stage")
                .isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT comment FROM reviews WHERE id = ?", String.class, reviewId))
                .as("the reviews adapter still purged the subject's comment although the media leg failed")
                .isNull();

        // Durable — the media tombstones are retryable, not lost.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, mediaAssetId))
                .as("the failing media leg left the retryable PURGE_PENDING tombstone")
                .isEqualTo("PURGE_PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_media WHERE uploader_id = ? AND status = 'PURGE_PENDING'",
                Integer.class, userId))
                .as("the review_media leg left the same retryable tombstone")
                .isEqualTo(1);

        // Durable — the registry keeps the purger's publication open for the
        // EventPublicationResubmission sweep (the pre-fix listener never
        // registered a publication at all).
        Integer openPublications = 0;
        deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            openPublications = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM event_publication WHERE listener_id LIKE ?",
                    Integer.class, "%AccountIdentityContentPurger%");
            if (openPublications != null && openPublications > 0) {
                break;
            }
            Thread.sleep(200);
        }
        assertThat(openPublications)
                .as("the unfinished erasure purge is recorded as an open event publication")
                .isGreaterThanOrEqualTo(1);

        // The erasure itself completed honestly (the row is pseudonymized).
        var row = jdbcTemplate.queryForMap(
                "SELECT subject, pseudonymized_at FROM users WHERE id = ?", userId);
        assertThat((String) row.get("subject")).startsWith("anon-");
        assertThat(row.get("pseudonymized_at")).isNotNull();
    }

    /** The isolation witnesses: a review row (comment to purge) + its review_media leg. */
    private UUID seedReviewAndReviewMedia(UUID userId) {
        UUID reviewId = UUID.randomUUID();
        // V85's dual-mode CHECK (ck_review_origin_booking): an ORGANIC review
        // carries no booking — the seeded witness needs no booking parents.
        jdbcTemplate.update(
                "INSERT INTO reviews (id, reviewer_id, provider_id, rating, comment, origin) "
                        + "VALUES (?, ?, ?, 5, ?, 'ORGANIC')",
                reviewId, userId, userId, AUTHORED_REVIEW_COMMENT);
        jdbcTemplate.update(
                "INSERT INTO review_media (id, review_id, uploader_id, object_key, content_type, "
                        + "size_bytes, status, position) VALUES (?, ?, ?, ?, 'image/jpeg', 51, 'UPLOADED', 1)",
                UUID.randomUUID(), reviewId, userId, "erasure/j48-outage-review-media.jpg");
        return reviewId;
    }

    // ------------------------------------------------------------------
    // The gate helpers (the house shape — PrivacyAccountJourneyIntegration-
    // Test's own: PKCE + client_secret_basic + the form login's CSRF dance).
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
                "Erasure Resilience Gate Integration Test Client");
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

    private HttpResponse<String> postJsonWithBearer(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(loginHtml);
        return reversed.find() ? reversed.group(1) : null;
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

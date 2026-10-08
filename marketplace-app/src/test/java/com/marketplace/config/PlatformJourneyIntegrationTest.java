package com.marketplace.config;

import test.config.AuthorizationServerFixture;
import test.config.IntegrationContainers;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.marketplace.orders.OrdersService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * A-16 (compliance plan E.2): the closing gate — «اختبار الرحلة الكاملة
 * الآلي (الأضلاع الستة)», the wave-E journey gate. One automated user
 * journey measures all six facets the compliance plan's §6 demands, on the
 * real chain: real PostgreSQL + real Redis + the real Flyway schema + the
 * real login gate's Bearer (the five-legged PKCE public client) + the real
 * Modulith event machinery + the real search module + the real Arabic
 * message bundle.
 *
 * <p><b>The six facets, in the journey's own order:</b>
 * <ol>
 * <li><b>ضلع عمر التطبيق (§7 moment 1):</b> the cold-boot readiness — the
 * anonymous deployment probe posture (GET /actuator/health/readiness
 * answering UP over the db/redis/diskSpace group).</li>
 * <li><b>العقد المفتوح للجوال (§7 moment 2):</b> the first screen's
 * contract — the public OpenAPI surface (GET /v3/api-docs) carrying the
 * journey's own paths (the cart, the orders, the search).</li>
 * <li><b>البحث/الاكتشاف:</b> the stranger's discovery channel — the public
 * full-text search (GET /api/v1/search) answering over the real search
 * module.</li>
 * <li><b>عربية/i18n:</b> the localized error contract — an invalid cart
 * line (quantity 0 → the bean-validation VAL-001) answered with
 * Accept-Language: ar carries the Arabic title from the real
 * messages_ar.properties bundle resolved at the request locale.</li>
 * <li><b>الخلفية (API + أحداث):</b> the buyer's cart fills over HTTP (the
 * union semantics) → the placement freezes the snapshot and derives the
 * total → the machine's guarded transitions run.</li>
 * <li><b>الإشعار:</b> the events DELIVER — the AFTER_COMMIT listener writes
 * the buyer's notification row on the real registry + late-lander chain
 * (measured as rows), and the terminal guard answers the honest 409.</li>
 * </ol>
 *
 * <p>The journey deliberately rides the OrderJourneyIntegrationTest idioms
 * (the A-11 gate's own helpers, per-class duplication the house pattern
 * records) — this gate is the SAME journey widened to the platform's six
 * facets, not a replacement for it.
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
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PlatformJourneyIntegrationTest {

    private static final String PASSWORD = "it-platform-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; configuration from the IntegrationContainers factory
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrdersService ordersService;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theSixFacetJourneyFromColdBootToTheNotifiedBuyer() throws Exception {
        // ---- FACET: ضلع عمر التطبيق (§7 moment 1) — the cold-boot readiness,
        // the anonymous deployment-probe posture (readiness = db+redis+diskSpace).
        HttpResponse<String> readiness = get("/actuator/health/readiness", null);
        assertThat(readiness.statusCode()).as("readiness probe: %s", body(readiness)).isEqualTo(200);
        assertThat(objectMapper.readTree(readiness.body()).path("status").asString())
                .as("the deployment gate's group is UP").isEqualTo("UP");

        // ---- FACET: العقد المفتوح للجوال (§7 moment 2) — the first screen's
        // contract: the public OpenAPI surface carries the journey's own paths.
        HttpResponse<String> apiDocs = get("/v3/api-docs", null);
        assertThat(apiDocs.statusCode()).as("api-docs: %s", body(apiDocs)).isEqualTo(200);
        JsonNode paths = objectMapper.readTree(apiDocs.body()).path("paths");
        assertThat(paths.has("/api/v1/me/cart/items")).as("the cart path in the mobile contract").isTrue();
        assertThat(paths.has("/api/v1/orders")).as("the orders path in the mobile contract").isTrue();
        assertThat(paths.has("/api/v1/search")).as("the search path in the mobile contract").isTrue();

        // ---- FACET: البحث/الاكتشاف — the stranger's discovery channel: the
        // public full-text search over the real search module (pg_trgm).
        HttpResponse<String> discovery = get("/api/v1/search?q=journey", null);
        assertThat(discovery.statusCode()).as("public search: %s", body(discovery)).isEqualTo(200);

        // ---- The buyer enters through the real login gate (the five-legged
        // PKCE public-client flow — §7 moment 4, the standing fact).
        String username = "it-journey-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(gate.accessToken()).isNotBlank();

        // ---- FACET: عربية/i18n — the localized error contract: the invalid
        // cart line (quantity 0 → the bean-validation VAL-001) answered at the
        // Arabic locale carries the real messages_ar.properties title.
        HttpResponse<String> arabicRejection = postJsonWithLanguage(
                "/api/v1/me/cart/items", gate.accessToken(), """
                        {"productId":"%s","quantity":0,"unitAmountMinor":1500,"currency":"SAR"}"""
                        .formatted(UUID.randomUUID()),
                "ar");
        assertThat(arabicRejection.statusCode())
                .as("the invalid line's rejection: %s", body(arabicRejection)).isEqualTo(400);
        assertThat(arabicRejection.body())
                .as("the VAL-001 title resolved from messages_ar at the request locale")
                .contains("طلب غير صالح");

        // ---- FACET: الخلفية (API + أحداث) — the cart fills over HTTP (the
        // union semantics on the duplicate add) and the placement freezes the
        // snapshot with the derived total.
        UUID product = UUID.randomUUID();
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":2,"unitAmountMinor":1500,"currency":"SAR"}"""
                .formatted(product));
        HttpResponse<String> unionAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":3,"unitAmountMinor":1500,"currency":"SAR"}"""
                .formatted(product));
        assertThat(unionAdd.statusCode()).as("duplicate add: %s", body(unionAdd)).isEqualTo(201);
        assertThat(objectMapper.readTree(unionAdd.body()).path("quantity").asInt())
                .as("the duplicate add collapses into the quantity bump").isEqualTo(5);
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1,"unitAmountMinor":9900,"currency":"SAR"}"""
                .formatted(UUID.randomUUID()));

        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("status").asString()).isEqualTo("PLACED");
        assertThat(order.path("totalAmountMinor").asLong())
                .as("the derived total: 5 x 1500 + 1 x 9900").isEqualTo(17400L);

        // The machine's guarded transition through the service.
        ordersService.confirm(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CONFIRMED");

        // ---- FACET: الإشعار — the events DELIVER: the AFTER_COMMIT listener
        // wrote the buyer's notification row on the real chain (the registry
        // + the notifications late-lander crossing, measured as rows).
        awaitNotificationRows(orderId, 1);
        Integer confirmedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_CONFIRMED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        assertThat(confirmedRows).as("the ORDER_CONFIRMED notification row").isEqualTo(1);

        // The closing honesty: the terminal guard answers the honest 409.
        ordersService.fulfill(orderId);
        HttpResponse<String> lateCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode()).as("cancel after fulfillment: %s", body(lateCancel)).isEqualTo(409);
    }

    // ---------- the OrderJourneyIntegrationTest idioms (the A-11 gate's own helpers) ----------

    private void registerUser(String username) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles("USER")
                .build());
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Platform Journey Gate Integration Test Client");
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
        assertThat(tokenResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(), sessionCookie);
    }

    private record GateResult(String accessToken, String sessionCookie) {
    }

    /**
     * The AFTER_COMMIT listener runs in its own REQUIRES_NEW unit after the
     * publishing transaction commits — on a loaded CI runner that hop is
     * not instantaneous, so the row assertion waits for it (bounded, the
     * house polling shape: 200ms steps, 10s ceiling).
     */
    private void awaitNotificationRows(UUID orderId, int minimum) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE message LIKE '%' || ? || '%'",
                    Integer.class, orderId);
            if (rows != null && rows >= minimum) {
                return;
            }
            Thread.sleep(200);
        }
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

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        return postJsonWithLanguage(path, accessToken, json, null);
    }

    private HttpResponse<String> postJsonWithLanguage(String path, String accessToken, String json,
            String acceptLanguage) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json");
        if (acceptLanguage != null) {
            builder.header("Accept-Language", acceptLanguage);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying delete is a method override string on the builder.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(c -> SESSION_COOKIE.matcher(c))
                .filter(Matcher::find)
                .map(m -> m.group(1) + "=" + m.group(2))
                .findFirst().orElse("");
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie.isBlank() ? fallback : cookie;
    }

    private String csrfTokenFrom(String html) {
        Matcher direct = CSRF_INPUT.matcher(html);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(html);
        return reversed.find() ? reversed.group(1) : "";
    }

    private String queryParam(String url, String param) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(param) + "=([^&]+)").matcher(url);
        assertThat(matcher.find()).as("%s must be present in %s", param, url).isTrue();
        return matcher.group(1);
    }

    private String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(300, response.body().length()));
    }
}

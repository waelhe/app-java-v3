package com.marketplace.orders;

import test.config.IntegrationContainers;
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
import test.config.AuthorizationServerFixture;
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
 * A-11 (compliance plan wave C: C.1) — the declared unit gate
 * "اختبار رحلة طلب" (the order journey): the machine measured end-to-end on
 * the real chain — real PostgreSQL + real Redis + the real Flyway schema
 * (V113's tables, V114/V115's widened type CHECK) + the real login gate's
 * Bearer + the real Modulith event machinery (the registry row and the
 * notifications late-lander listener).
 *
 * <p>The journey: the buyer fills the cart over HTTP → places the order
 * (the placement transaction: frozen lines + derived total + the cart
 * tombstone) → the merchant transitions run through the service (the
 * machine's guarded edges, the surfaces the store's merchant console will
 * ride) → the events DELIVER (the AFTER_COMMIT listener writes the buyer's
 * notification rows) → the terminal guard answers 409 over HTTP → the
 * privacy contract answers an honest 404 to a stranger.
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
class OrderJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-order-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

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
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void theFullOrderJourneyFromCartToTerminalState() throws Exception {
        String username = "it-order-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(gate.accessToken()).isNotBlank();

        // (1) The cart fills over HTTP — two lines, the union semantics on
        // the third add (the duplicate product raises the quantity).
        UUID product = UUID.randomUUID();
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":2,"unitAmountMinor":1500,"currency":"SAR"}"""
                .formatted(product));
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1,"unitAmountMinor":9900,"currency":"SAR"}"""
                .formatted(UUID.randomUUID()));
        HttpResponse<String> unionAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":3,"unitAmountMinor":1500,"currency":"SAR"}"""
                .formatted(product));
        assertThat(unionAdd.statusCode()).as("duplicate add: %s", body(unionAdd)).isEqualTo(201);
        JsonNode unionLine = objectMapper.readTree(unionAdd.body());
        assertThat(unionLine.path("quantity").asInt())
                .as("the duplicate add collapses into the quantity bump").isEqualTo(5);

        HttpResponse<String> cartRead = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(cartRead.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(cartRead.body()).size()).isEqualTo(2);

        // (2) The placement — the frozen snapshot and the derived total.
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("status").asString()).isEqualTo("PLACED");
        assertThat(order.path("totalAmountMinor").asLong())
                .as("the derived total: 5 x 1500 + 1 x 9900").isEqualTo(17400L);
        assertThat(order.path("items").size()).isEqualTo(2);

        // The cart is tombstoned: the next read is empty and a second
        // placement answers the 409 contract.
        HttpResponse<String> cartAfter = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(objectMapper.readTree(cartAfter.body()).size()).isZero();
        HttpResponse<String> secondPlacement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(secondPlacement.statusCode()).isEqualTo(409);

        // (3) The machine's merchant transitions through the service: the
        // guarded edges the store's merchant console (A-17) will ride.
        ordersService.confirm(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CONFIRMED");
        ordersService.fulfill(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("FULFILLED");

        // (4) The events DELIVER — the AFTER_COMMIT listener wrote the
        // buyer's notification rows on the real chain (the registry + the
        // late-lander crossing, measured as rows).
        awaitNotificationRows(orderId, 2);
        Integer confirmedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_CONFIRMED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        Integer fulfilledRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_FULFILLED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        assertThat(confirmedRows).as("ORDER_CONFIRMED notification row").isEqualTo(1);
        assertThat(fulfilledRows).as("ORDER_FULFILLED notification row").isEqualTo(1);

        // (5) The terminal guard over HTTP: a fulfilled order does not
        // reopen — the honest 409.
        HttpResponse<String> lateCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode()).as("cancel after fulfillment: %s", body(lateCancel)).isEqualTo(409);

        // (6) The privacy contract: a stranger gets an honest 404.
        String stranger = "it-order-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(stranger);
        GateResult strangerGate = loginGate(stranger, PASSWORD);
        HttpResponse<String> strangerRead = getWithBearer(
                "/api/v1/orders/" + orderId, strangerGate.accessToken());
        assertThat(strangerRead.statusCode())
                .as("stranger read: existence itself is private").isEqualTo(404);
    }

    @Test
    void theCancellationBranchPublishesAndNotifies() throws Exception {
        String username = "it-order-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);

        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1,"unitAmountMinor":4200,"currency":"SAR"}"""
                .formatted(UUID.randomUUID()));
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(objectMapper.readTree(placement.body()).path("id").asString());

        HttpResponse<String> cancellation = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(),
                "{\"reason\":\"buyer changed mind\"}");
        assertThat(cancellation.statusCode()).as("cancel: %s", body(cancellation)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancellation.body()).path("status").asString())
                .isEqualTo("CANCELLED");

        awaitNotificationRows(orderId, 1);
        Integer cancelledRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_CANCELLED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        assertThat(cancelledRows).as("ORDER_CANCELLED notification row").isEqualTo(1);

        // A cancelled order is terminal too — the second cancel answers 409.
        HttpResponse<String> secondCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(),
                "{\"reason\":\"twice\"}");
        assertThat(secondCancel.statusCode()).isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // The login gate machinery (the L23 five-step sequence, house verbatim)
    // ------------------------------------------------------------------

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
                "Order Journey Gate Integration Test Client");
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

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        return get(baseUrl() + path, null, accessToken);
    }

    private HttpResponse<String> get(String url, String sessionCookie, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
        // 2000 (was 300): a ProblemDetail's fieldErrors — the piece that
        // names the violated constraint — live past the traceId; the 300-char
        // window cut them off in the measured CI failures of 2026-10-09,
        // leaving a "Validation failed" with no subject.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}

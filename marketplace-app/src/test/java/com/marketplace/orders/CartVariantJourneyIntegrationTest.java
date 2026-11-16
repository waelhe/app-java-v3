package com.marketplace.orders;

import org.junit.jupiter.api.BeforeEach;
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
import test.config.IntegrationContainers;
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
 * ADR-0012 (the merchandise model's variant depth — the cart-integration
 * slice): the declared journey gate for the variant-aware cart, measured
 * end-to-end on the real chain — real PostgreSQL + real Redis + the real
 * Flyway schema (V222's widened union keys and the V219 variant shelves)
 * + the real login gate's Bearer + Envers writing the audit trail.
 *
 * <p>The journey: the seller provisions a product with TWO variants → the
 * buyer fills the cart with a variant line, the SAME product's
 * product-level line, and a second variant line (the widened union key's
 * own proof: the product-level line and the variant lines of one product
 * COEXIST) → a duplicate variant add collapses into the quantity bump →
 * a variant from a foreign product answers the honest 400 → a suspended
 * variant answers the house 409 → the placement freezes the
 * placement-instant variant prices and the variant references into the
 * immutable order lines → the reservation spans BOTH shelves
 * (products.reserved_quantity and product_variants.reserved_quantity) →
 * the payment intent carries the derived total → fulfillment commits both
 * shelves (the real deductions) → the cart tombstones once.
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
class CartVariantJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-cart-variant-journey-password";
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

    /** The machine's merchant transitions ride the service (the A-17 console is not this slice's surface). */
    @Autowired
    private OrdersService ordersService;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedTheStoreDictionary() {
        // The journey's category arrives as DATA (the V116 stance — never a
        // migration), seeded by the class itself (the run-order lesson the
        // order journey recorded: never rely on another class's seed).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-cart-variants", "Journey cart variants",
                "خيارات سلة الرحلة", 0);
    }

    @Test
    void theVariantCartJourneyFromAddToTheFrozenSnapshotAndBothShelves() throws Exception {
        // The seller provisions FIRST — the product, its variants, and both
        // shelves (the product-level stock the zero-variant line buys, the
        // variants' own shelves the variant lines buy).
        String sellerName = "it-cartv-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken(), 200000L);
        setProductStock(sellerGate.accessToken(), product, 10);

        HttpResponse<String> tall = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-CUP-TALL","name":"Tall / 12oz","priceMinor":249900}""");
        assertThat(tall.statusCode()).as("variant register (TALL): %s", body(tall)).isEqualTo(201);
        UUID tallId = UUID.fromString(objectMapper.readTree(tall.body()).path("id").asString());

        HttpResponse<String> grande = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-CUP-GRANDE","name":"Grande / 16oz","priceMinor":299900}""");
        assertThat(grande.statusCode()).as("variant register (GRANDE): %s", body(grande)).isEqualTo(201);
        UUID grandeId = UUID.fromString(objectMapper.readTree(grande.body()).path("id").asString());

        HttpResponse<String> shortSuspended = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-CUP-SHORT","name":"Short / 8oz","priceMinor":199900}""");
        assertThat(shortSuspended.statusCode()).as("variant register (SHORT): %s", body(shortSuspended))
                .isEqualTo(201);
        UUID shortId = UUID.fromString(objectMapper.readTree(shortSuspended.body()).path("id").asString());

        setVariantStock(sellerGate.accessToken(), tallId, 5);
        setVariantStock(sellerGate.accessToken(), grandeId, 3);
        // SHORT is born suspended — the buyer-invisible surface of the
        // variant's own lifecycle.
        HttpResponse<String> suspendShort = postJson(
                "/api/v1/store/variants/" + shortId + "/suspend", sellerGate.accessToken(), "");
        assertThat(suspendShort.statusCode()).as("variant suspend: %s", body(suspendShort)).isEqualTo(200);

        String username = "it-cartv-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the buyer's /me provisioning sync").isEqualTo(200);

        // (1) The cart fills: the TALL variant line, the SAME product's
        // product-level line, and the GRANDE variant line — the widened
        // union key's own proof (three coexisting lines over ONE product:
        // the COALESCE key keeps the product-level collapse for
        // product-level lines only).
        HttpResponse<String> addTall = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":2}"""
                .formatted(product, tallId));
        assertThat(addTall.statusCode()).as("variant line add: %s", body(addTall)).isEqualTo(201);
        JsonNode tallLine = objectMapper.readTree(addTall.body());
        assertThat(tallLine.path("variantId").asString()).isEqualTo(tallId.toString());
        assertThat(tallLine.path("unitAmountMinor").asLong())
                .as("the variant's own authoritative price, never the product's")
                .isEqualTo(249900L);

        HttpResponse<String> addPlain = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}""".formatted(product));
        assertThat(addPlain.statusCode()).as("product-level line add beside its own variants: %s",
                body(addPlain)).isEqualTo(201);
        JsonNode plainLine = objectMapper.readTree(addPlain.body());
        assertThat(plainLine.path("variantId").isNull()).as("the product-level line carries no variant").isTrue();
        assertThat(plainLine.path("unitAmountMinor").asLong()).isEqualTo(200000L);

        HttpResponse<String> addGrande = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":1}"""
                .formatted(product, grandeId));
        assertThat(addGrande.statusCode()).as("second variant line add: %s", body(addGrande)).isEqualTo(201);

        // (2) The union semantics per variant: a duplicate TALL add raises
        // the quantity instead of a second line.
        HttpResponse<String> unionAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":1}"""
                .formatted(product, tallId));
        assertThat(unionAdd.statusCode()).as("duplicate variant add: %s", body(unionAdd)).isEqualTo(201);
        assertThat(objectMapper.readTree(unionAdd.body()).path("quantity").asInt())
                .as("the duplicate variant add collapsed into the quantity bump").isEqualTo(3);

        HttpResponse<String> cartRead = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(cartRead.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(cartRead.body()).size())
                .as("three coexisting lines: two variants + the product-level line").isEqualTo(3);

        // (3) The honest guards: a variant from a foreign product answers
        // 400; a suspended variant under an active product answers 409.
        UUID foreignProduct = registerProduct(sellerGate.accessToken(), 100L);
        HttpResponse<String> mismatch = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":1}"""
                .formatted(foreignProduct, tallId));
        assertThat(mismatch.statusCode()).as("variant/product mismatch: %s", body(mismatch)).isEqualTo(400);

        HttpResponse<String> suspendedAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":1}"""
                .formatted(product, shortId));
        assertThat(suspendedAdd.statusCode()).as("suspended variant add: %s", body(suspendedAdd)).isEqualTo(409);

        // (4) The placement: the frozen snapshot names the variants, the
        // derived total is the three lines' sum
        // (3 × 249900) + (1 × 200000) + (1 × 299900) = 1249600.
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("totalAmountMinor").asLong()).isEqualTo(1249600L);
        assertThat(order.path("items").size()).isEqualTo(3);

        java.util.Map<UUID, long[]> frozen = new java.util.HashMap<>();
        for (JsonNode item : order.path("items")) {
            String variantId = item.path("variantId").isNull() ? null : item.path("variantId").asString();
            if (variantId == null) {
                assertThat(item.path("unitAmountMinor").asLong()).isEqualTo(200000L);
            } else {
                frozen.put(UUID.fromString(variantId),
                        new long[]{item.path("quantity").asInt(), item.path("unitAmountMinor").asLong()});
            }
        }
        assertThat(frozen).as("the two variant lines froze with their references").hasSize(2);
        assertThat(frozen.get(tallId)).as("TALL froze 3 × 249900").containsExactly(3, 249900L);
        assertThat(frozen.get(grandeId)).as("GRANDE froze 1 × 299900").containsExactly(1, 299900L);

        // (5) The reservation spans BOTH shelves — measured in the database.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM product_variants WHERE id = ?", Integer.class, tallId))
                .as("the TALL variant's shelf holds the placement's reservation").isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM product_variants WHERE id = ?", Integer.class, grandeId))
                .as("the GRANDE variant's shelf holds the placement's reservation").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the product-level line reserved the PRODUCT shelf (the zero-variant rule's own row)")
                .isEqualTo(1);

        // (6) The payment intent carries the derived total (the frozen
        // lines' sum through the existing engine — no variant knowledge
        // leaks into payments).
        HttpResponse<String> intent = postJson("/api/v1/orders/" + orderId + "/payment-intent",
                gate.accessToken(), "");
        assertThat(intent.statusCode()).as("payment intent: %s", body(intent)).isEqualTo(201);
        assertThat(objectMapper.readTree(intent.body()).path("amountCents").asLong())
                .isEqualTo(1249600L);

        // (7) The merchant transitions commit BOTH shelves: the variant
        // reservations become real deductions, the product-level one too.
        ordersService.confirm(orderId);
        ordersService.fulfill(orderId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?", Integer.class, tallId))
                .as("TALL's real deduction: 5 - 3").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM product_variants WHERE id = ?", Integer.class, tallId))
                .as("the commit released TALL's reservation").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?", Integer.class, grandeId))
                .as("GRANDE's real deduction: 3 - 1").isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM product_variants WHERE id = ?", Integer.class, grandeId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the product-level line's real deduction: 10 - 1").isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, product)).isZero();

        // (8) The cart tombstones once — the next read is empty.
        HttpResponse<String> cartAfter = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(objectMapper.readTree(cartAfter.body()).size()).isZero();

        // (9) The Envers mirror carries the trail (the V33 lesson's proof —
        // the variant columns joined the aud twins in V222, and this
        // journey would have caught a missing twin).
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM cart_items_aud WHERE variant_id IS NOT NULL", Integer.class);
        assertThat(auditRows).as("the variant cart lines' Envers trail").isGreaterThanOrEqualTo(2);
        Integer orderAuditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM order_items_aud WHERE variant_id IS NOT NULL", Integer.class);
        assertThat(orderAuditRows).as("the frozen variant order lines' Envers trail").isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------
    // The provisioning helpers (the order journey's exact shapes).
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

    private UUID registerProduct(String sellerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-cart-variants","title":"Journey variant cup",
                 "description":"ADR-0012 cart-integration stock","priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private void setProductStock(String sellerToken, UUID productId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut(
                "/api/v1/store/products/" + productId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("product stock write: %s", body(stocked)).isEqualTo(200);
    }

    private void setVariantStock(String sellerToken, UUID variantId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut(
                "/api/v1/store/variants/" + variantId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("variant stock write: %s", body(stocked)).isEqualTo(200);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Cart Variant Journey Gate Integration Test Client");
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

    private HttpResponse<String> postJsonOrPut(String path, String accessToken, String json,
                                                String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
        return response.body() == null ? "" : response.body().substring(0,
                Math.min(2000, response.body().length()));
    }
}

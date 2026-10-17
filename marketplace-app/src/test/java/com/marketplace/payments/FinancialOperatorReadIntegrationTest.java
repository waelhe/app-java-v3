package com.marketplace.payments;

import test.config.IntegrationContainers;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * The payments plan's Phase-1 unified finance-operator read model, proven
 * on the real chain (payments plan §8 acceptance journey 7: "an authorized
 * finance operator can investigate any origin by a single search … and
 * verify provider/payment/ledger/source state"). The journey measured
 * end-to-end:
 *
 * <ol>
 *   <li>the commerce leg stands up a REAL payable ORDER (the seller's
 *   stock, the buyer's cart, the placement, the ORDER-origin intent with
 *   the ADR-0013 frozen commission rate);</li>
 *   <li>the money leg settles through the ADMIN command shell
 *   ({@code POST /payments/intents/{id}/confirm}) — the same settlement
 *   the verified webhook reaches — so the Modulith event machinery
 *   carries COMPLETED to the ledger's allocation listener on the real
 *   registry path;</li>
 *   <li>the OPERATOR leg: a single search by the origin-agnostic subject
 *   id finds the intent across every origin's shape, the frozen rate and
 *   the PSP linkage ride the row, and the unified financial-facts read
 *   composes the payments summary with the ledger's own allocation —
 *   the credit, the commission posted at the FROZEN rate (D2), the
 *   seller's balance, and the net;</li>
 *   <li>the authorization contract: a plain user's search answers the
 *   honest 403, an unknown status word answers the honest 400.</li>
 * </ol>
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
class FinancialOperatorReadIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-finops-journey-password";
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

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedTheStoreDictionary() {
        // The journey's category arrives as DATA (the V116 rule verbatim —
        // never a migration), seeded idempotently by the class itself.
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
    }

    @Test
    void theOperatorInvestigatesASettledOrderByASingleSearch() throws Exception {
        // (1) The commerce leg — a REAL payable ORDER.
        String sellerName = "it-finops-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken(), 1500L);
        setStock(sellerGate.accessToken(), product, 10);

        String buyerName = "it-finops-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName);
        GateResult buyerGate = loginGate(buyerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", buyerGate.accessToken()).statusCode())
                .as("the buyer's /me provisioning sync").isEqualTo(200);

        assertThat(postJson("/api/v1/me/cart/items", buyerGate.accessToken(), """
                {"productId":"%s","quantity":2}"""
                .formatted(product)).statusCode()).isEqualTo(201);
        HttpResponse<String> placement = postJson("/api/v1/orders", buyerGate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        UUID orderId = UUID.fromString(objectMapper.readTree(placement.body()).path("id").asString());

        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", buyerGate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        JsonNode intentNode = objectMapper.readTree(intentResponse.body());
        // The order's intent surface answers the shared PaymentIntentDetails
        // carrier — the intent's own key field is paymentIntentId.
        UUID intentId = UUID.fromString(intentNode.path("paymentIntentId").asString());
        assertThat(intentNode.path("origin").asString()).isEqualTo("ORDER");

        // (2) The money leg — the ADMIN settlement command publishes
        // COMPLETED on the real event chain; the ledger's allocation
        // listener (ADR-0013 D2) posts at the FROZEN rate.
        String adminName = "it-finops-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);

        HttpResponse<String> confirm = postJson("/api/v1/payments/intents/" + intentId + "/confirm",
                adminGate.accessToken(), "{\"externalId\":\"it-finops-" + intentId + "\"}");
        assertThat(confirm.statusCode()).as("the admin settlement: %s", body(confirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(confirm.body()).path("status").asString())
                .isEqualTo("SUCCEEDED");

        // The allocation's asynchronous hop: the AFTER_COMMIT listener
        // posts the credit and the commission on the real registry path —
        // the typed wait polls the ledger's OWN deterministic keys (the
        // adapter's derivations, the writer paths' verbatim).
        UUID commissionKey = UUID.nameUUIDFromBytes(
                ("commission-" + intentId.toString()).getBytes(StandardCharsets.UTF_8));
        awaitLedgerEntries(intentId, commissionKey, 2);

        // (3) The OPERATOR leg — a single search by the origin-agnostic
        // subject id: the pasted ORDER id finds the intent.
        HttpResponse<String> search = getWithBearer(
                "/api/v1/admin/payments?subjectId=" + orderId, adminGate.accessToken());
        assertThat(search.statusCode()).as("the operator's search: %s", body(search)).isEqualTo(200);
        JsonNode searchPage = objectMapper.readTree(search.body());
        assertThat(searchPage.path("totalElements").asLong())
                .as("the single search finds the order's intent").isEqualTo(1);
        JsonNode row = searchPage.path("content").path(0);
        assertThat(row.path("id").asString()).isEqualTo(intentId.toString());
        assertThat(row.path("origin").asString()).isEqualTo("ORDER");
        assertThat(row.path("orderId").asString()).isEqualTo(orderId.toString());
        assertThat(row.path("status").asString()).isEqualTo("SUCCEEDED");
        // NullNode.decimalValue() answers ZERO, not null — the honest
        // check reads the node shape itself.
        assertThat(row.path("commissionRate").isNull())
                .as("the ADR-0013 frozen rate rides the row (non-null post-V220 intent)")
                .isFalse();
        BigDecimal frozenRate = row.path("commissionRate").decimalValue();

        // The same search narrowed by origin and status — the filters
        // AND together (the journey's "any origin" narrowed at will).
        HttpResponse<String> narrowed = getWithBearer(
                "/api/v1/admin/payments?origin=ORDER&status=SUCCEEDED&consumerId="
                        + row.path("consumerId").asString(), adminGate.accessToken());
        assertThat(narrowed.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(narrowed.body()).path("totalElements").asLong())
                .as("the narrowed search still finds it").isEqualTo(1);

        // (4) The unified financial-facts read — payments' own summary
        // COMPOSED with the ledger's allocation.
        HttpResponse<String> facts = getWithBearer(
                "/api/v1/admin/payments/" + intentId + "/financial-facts", adminGate.accessToken());
        assertThat(facts.statusCode()).as("the financial facts: %s", body(facts)).isEqualTo(200);
        JsonNode factsNode = objectMapper.readTree(facts.body());
        long amountMinor = factsNode.path("intent").path("amountCents").asLong();
        assertThat(amountMinor).as("the frozen snapshot's derived total: 2 x 1500").isEqualTo(3000L);

        long expectedCommission = BigDecimal.valueOf(amountMinor)
                .multiply(frozenRate)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        JsonNode entries = factsNode.path("ledgerEntries");
        assertThat(entries.size()).as("the allocation's two postings").isEqualTo(2);
        java.util.Set<String> entryTypes = new java.util.HashSet<>();
        long credit = 0L;
        long commission = 0L;
        for (JsonNode entry : entries) {
            entryTypes.add(entry.path("entryType").asString());
            switch (entry.path("entryType").asString()) {
                case "PAYMENT_CREDIT" -> credit = entry.path("amountCents").asLong();
                case "COMMISSION_DEBIT" -> commission = entry.path("amountCents").asLong();
                default -> { /* the vocabulary check below refuses anything else */ }
            }
        }
        assertThat(entryTypes)
                .as("only the intent-keyed postings ride the allocation")
                .containsExactlyInAnyOrder("PAYMENT_CREDIT", "COMMISSION_DEBIT");
        assertThat(credit).as("the settlement credit is the whole amount").isEqualTo(amountMinor);
        assertThat(commission).as("the commission posts at the FROZEN rate (D2, HALF_UP)")
                .isEqualTo(expectedCommission);

        JsonNode balances = factsNode.path("providerBalances");
        assertThat(balances.size()).as("the seller's balance row").isEqualTo(1);
        assertThat(balances.path(0).path("balanceCents").asLong())
                .as("the seller's balance: amount minus the frozen commission")
                .isEqualTo(amountMinor - expectedCommission);

        JsonNode net = factsNode.path("netAllocation");
        assertThat(net.size()).isEqualTo(1);
        assertThat(net.path(0).path("amountCents").asLong())
                .as("the net allocation: credit minus commission").isEqualTo(amountMinor - expectedCommission);
        assertThat(net.path(0).path("currency").asString()).isEqualTo("SAR");

        // (5) The authorization contract — a plain user's search answers
        // the honest 403; an unknown status word answers the honest 400.
        HttpResponse<String> forbidden = getWithBearer(
                "/api/v1/admin/payments?subjectId=" + orderId, buyerGate.accessToken());
        assertThat(forbidden.statusCode()).as("a plain user cannot search the money surface")
                .isEqualTo(403);
        HttpResponse<String> badStatus = getWithBearer(
                "/api/v1/admin/payments?status=NOT_A_STATE", adminGate.accessToken());
        assertThat(badStatus.statusCode()).as("a status typo is a 400, never a silent empty page")
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // The allocation's typed wait — the ledger's own deterministic keys
    // (the house polling shape: 200ms steps, 10s ceiling).
    // ------------------------------------------------------------------

    private void awaitLedgerEntries(UUID intentId, UUID commissionKey, int minimum)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ledger_entries WHERE source_id IN (?, ?)",
                    Integer.class, intentId, commissionKey);
            if (rows != null && rows >= minimum) {
                return;
            }
            Thread.sleep(200);
        }
    }

    // ------------------------------------------------------------------
    // The login gate machinery (the L23 five-step sequence, house verbatim)
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
                {"storeCategoryCode":"journey-appliances","title":"Finops journey product",
                 "description":"The finance-operator read model's payable stock",
                 "priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return java.util.UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private void setStock(String sellerToken, UUID productId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/products/" + productId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("stock write: %s", body(stocked)).isEqualTo(200);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Financial Operator Read Integration Test Client");
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

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body();
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

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String randomCodeVerifier() {
        return UUID.randomUUID().toString();
    }
}

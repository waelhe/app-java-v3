package com.marketplace.catalog;

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
 * ADR-0012 (the merchandise model's variant depth) — the declared journey
 * gate for the variant surface: the seller's write/read path measured
 * end-to-end on the real chain — real PostgreSQL + real Redis + the real
 * Flyway schema (V219's table, CHECK family and unique index) + the real
 * login gate's Bearer + Envers writing the audit trail.
 *
 * <p>The journey: the seller provisions a product → registers two
 * variants over HTTP (the service's own provider/currency copy proven on
 * the wire) → the roster read is SKU-ascending → a namespace collision
 * answers the house 409 with the collision named → another seller's
 * identical SKU is FREE (the namespace is the seller's own) → the shelf
 * write lands and is measured in the database → the availability
 * lifecycle runs to its terminal edge (ARCHIVED answers 409 on the way
 * out) → a stranger's read and write answer the honest 404 → the Envers
 * mirror carries the trail.
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
class ProductVariantJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-variant-journey-password";
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
        // The journey's category arrives as DATA (the V116 stance — never a
        // migration), seeded by the class itself (the run-order lesson the
        // order journey recorded: never rely on another class's seed).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-variants", "Journey variants", "خيارات الرحلة", 0);
    }

    @Test
    void theVariantJourneyFromRegistrationToTheTerminalEdge() throws Exception {
        // The seller provisions FIRST — the product must exist before any
        // variant lands under it (the ownership gate resolves through it).
        String sellerName = "it-variant-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken());

        // (1) Two variants register over HTTP — the service's own copy
        // proven on the wire: the currency is the product's own SAR, the
        // provider is the seller, the stock starts at zero.
        HttpResponse<String> red = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-RED","name":"Red / 2-cup","priceMinor":149900}""");
        assertThat(red.statusCode()).as("variant register: %s", body(red)).isEqualTo(201);
        JsonNode redVariant = objectMapper.readTree(red.body());
        UUID redId = UUID.fromString(redVariant.path("id").asString());
        assertThat(redVariant.path("sku").asString()).isEqualTo("JOURNEY-RED");
        assertThat(redVariant.path("currency").asString())
                .as("the currency is the product's own denomination, never client-supplied")
                .isEqualTo("SAR");
        assertThat(redVariant.path("providerId").asString()).isEqualTo(sellerId(sellerName));
        assertThat(redVariant.path("stockQuantity").asInt()).isZero();
        assertThat(redVariant.path("status").asString()).isEqualTo("ACTIVE");

        HttpResponse<String> blue = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-BLUE","name":"Blue / 2-cup","priceMinor":139900}""");
        assertThat(blue.statusCode()).as("second variant register: %s", body(blue)).isEqualTo(201);
        UUID blueId = UUID.fromString(objectMapper.readTree(blue.body()).path("id").asString());

        // (2) The roster read — SKU-ascending (the index's own order).
        HttpResponse<String> roster = getWithBearer("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken());
        assertThat(roster.statusCode()).isEqualTo(200);
        JsonNode rosterBody = objectMapper.readTree(roster.body());
        assertThat(rosterBody.size()).isEqualTo(2);
        assertThat(rosterBody.get(0).path("sku").asString()).isEqualTo("JOURNEY-BLUE");
        assertThat(rosterBody.get(1).path("sku").asString()).isEqualTo("JOURNEY-RED");

        // (3) A namespace collision answers the house 409 with the
        // collision named — the seller's own space is unique.
        HttpResponse<String> collision = postJson("/api/v1/store/products/" + product + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"JOURNEY-RED","name":"Duplicate","priceMinor":1}""");
        assertThat(collision.statusCode()).as("sku collision: %s", body(collision)).isEqualTo(409);
        assertThat(collision.body()).contains("JOURNEY-RED");

        // (4) Another seller's identical SKU is FREE — the namespace is
        // the seller's own (the ADR's decision 3, proven on the wire).
        String otherSellerName = "it-variant-other-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(otherSellerName, "USER", "PROVIDER");
        GateResult otherGate = loginGate(otherSellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", otherGate.accessToken()).statusCode()).isEqualTo(200);
        UUID otherProduct = registerProduct(otherGate.accessToken());
        HttpResponse<String> sameSku = postJson("/api/v1/store/products/" + otherProduct + "/variants",
                otherGate.accessToken(), """
                        {"sku":"JOURNEY-RED","name":"Their own red","priceMinor":99900}""");
        assertThat(sameSku.statusCode()).as("the same SKU in another seller's namespace: %s", body(sameSku))
                .isEqualTo(201);

        // (5) The shelf write lands and is measured in the database.
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/variants/" + redId + "/inventory",
                sellerGate.accessToken(), "{\"stockQuantity\":7}", "PUT");
        assertThat(stocked.statusCode()).as("variant stock write: %s", body(stocked)).isEqualTo(200);
        Integer shelf = jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?", Integer.class, redId);
        assertThat(shelf).as("the shelf write measured in the database").isEqualTo(7);

        // (6) The availability lifecycle runs to its terminal edge.
        HttpResponse<String> suspended = postJson("/api/v1/store/variants/" + blueId + "/suspend",
                sellerGate.accessToken(), "");
        assertThat(suspended.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(suspended.body()).path("status").asString()).isEqualTo("SUSPENDED");

        HttpResponse<String> archived = postJson("/api/v1/store/variants/" + blueId + "/archive",
                sellerGate.accessToken(), "");
        assertThat(archived.statusCode()).isEqualTo(200);
        String terminalStatus = jdbcTemplate.queryForObject(
                "SELECT variant_status FROM product_variants WHERE id = ?", String.class, blueId);
        assertThat(terminalStatus).isEqualTo("ARCHIVED");

        // ARCHIVED is terminal — the way out answers the house 409.
        HttpResponse<String> resurrect = postJson("/api/v1/store/variants/" + blueId + "/reactivate",
                sellerGate.accessToken(), "");
        assertThat(resurrect.statusCode()).as("the terminal edge: %s", body(resurrect)).isEqualTo(409);

        // (7) The privacy contract — a stranger's read and write both
        // answer the honest 404 (existence itself is not public here).
        HttpResponse<String> strangerRead = getWithBearer("/api/v1/store/variants/" + redId,
                otherGate.accessToken());
        assertThat(strangerRead.statusCode()).as("the stranger's read").isEqualTo(404);
        HttpResponse<String> strangerWrite = postJson("/api/v1/store/products/" + product + "/variants",
                otherGate.accessToken(), """
                        {"sku":"STRANGER-SKU","name":"Not mine","priceMinor":1}""");
        assertThat(strangerWrite.statusCode()).as("the stranger's write under someone's product").isEqualTo(404);

        // (8) The Envers mirror carries the trail (the V33 lesson's proof:
        // a base table without its _aud twin breaks audit INSERTs
        // silently — this journey would have caught it).
        Integer auditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variants_aud WHERE id = ?", Integer.class, blueId);
        assertThat(auditRows).as("the variant's Envers trail").isGreaterThanOrEqualTo(3);
    }

    @Test
    void theTypedProductLifecycleStoresValuesAndRejectsDuplicateVariantCombinations() throws Exception {
        String sellerName = "it-typed-c1-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        TypedProductSchema schema = seedTypedProductSchema();

        // The typed product starts as a draft. Its required PRODUCT attribute
        // is written against the ProductType assignment, not a StoreCategory field.
        String createBody = """
                {"storeCategoryCode":"journey-variants","title":"Typed C1 Product",
                 "description":"before edit","priceMinor":100000,"currency":"SAR",
                 "productTypeId":"%s",
                 "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                """.formatted(schema.productTypeId(), schema.materialAssignmentId(), schema.cottonOptionId());
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerGate.accessToken(), createBody);
        assertThat(created.statusCode()).as("typed product create: %s", body(created)).isEqualTo(201);
        JsonNode productBody = objectMapper.readTree(created.body());
        UUID productId = UUID.fromString(productBody.path("id").asString());
        assertThat(productBody.path("productTypeId").asString()).isEqualTo(schema.productTypeId().toString());
        assertThat(productBody.path("status").asString()).isEqualTo("DRAFT");

        HttpResponse<String> initialAttributes = getWithBearer(
                "/api/v1/store/products/" + productId + "/attributes", sellerGate.accessToken());
        assertThat(initialAttributes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(initialAttributes.body()).get(0).path("optionId").asString())
                .isEqualTo(schema.cottonOptionId().toString());

        // Replace the typed value and edit the product before publication.
        HttpResponse<String> replaced = postJsonOrPut("/api/v1/store/products/" + productId + "/attributes",
                sellerGate.accessToken(), """
                        [{"assignmentId":"%s","optionId":"%s"}]
                        """.formatted(schema.materialAssignmentId(), schema.linenOptionId()), "PUT");
        assertThat(replaced.statusCode()).as("typed attribute edit: %s", body(replaced)).isEqualTo(200);
        assertThat(objectMapper.readTree(replaced.body()).get(0).path("optionId").asString())
                .isEqualTo(schema.linenOptionId().toString());

        HttpResponse<String> edited = postJsonOrPut("/api/v1/store/products/" + productId,
                sellerGate.accessToken(), """
                        {"title":"Typed C1 Product Revised","description":"edited","priceMinor":110000}
                        """, "PATCH");
        assertThat(edited.statusCode()).as("product edit: %s", body(edited)).isEqualTo(200);
        assertThat(objectMapper.readTree(edited.body()).path("title").asString())
                .isEqualTo("Typed C1 Product Revised");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_type_id FROM products WHERE id = ?", UUID.class, productId))
                .isEqualTo(schema.productTypeId());

        // Create one variant for each buyer-selectable color combination.
        HttpResponse<String> red = postJson("/api/v1/store/products/" + productId + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"C1-TYPED-RED","name":"Red","priceMinor":110000,
                         "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                        """.formatted(schema.colorAssignmentId(), schema.redOptionId()));
        assertThat(red.statusCode()).as("red variant: %s", body(red)).isEqualTo(201);
        UUID redId = UUID.fromString(objectMapper.readTree(red.body()).path("id").asString());
        assertThat(objectMapper.readTree(red.body()).path("optionCombinationKey").asString())
                .matches("[0-9a-f]{64}");

        HttpResponse<String> redReadback = getWithBearer(
                "/api/v1/store/variants/" + redId + "/attributes", sellerGate.accessToken());
        assertThat(redReadback.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(redReadback.body()).get(0).path("optionId").asString())
                .isEqualTo(schema.redOptionId().toString());

        HttpResponse<String> duplicateCombination = postJson("/api/v1/store/products/" + productId + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"C1-TYPED-RED-DUP","name":"Another red","priceMinor":109000,
                         "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                        """.formatted(schema.colorAssignmentId(), schema.redOptionId()));
        assertThat(duplicateCombination.statusCode())
                .as("same typed option combination must be refused even with a different SKU: %s", body(duplicateCombination))
                .isEqualTo(409);

        HttpResponse<String> blue = postJson("/api/v1/store/products/" + productId + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"C1-TYPED-BLUE","name":"Blue","priceMinor":109000,
                         "attributeValues":[{"assignmentId":"%s","optionId":"%s"}]}
                        """.formatted(schema.colorAssignmentId(), schema.blueOptionId()));
        assertThat(blue.statusCode()).as("distinct variant combination: %s", body(blue)).isEqualTo(201);

        // Explicit publication flips DRAFT to ACTIVE; only ACTIVE products
        // may be reserved by the existing cart/order invariant.
        HttpResponse<String> published = postJson("/api/v1/store/products/" + productId + "/publish",
                sellerGate.accessToken(), "");
        assertThat(published.statusCode()).as("product publish: %s", body(published)).isEqualTo(200);
        assertThat(objectMapper.readTree(published.body()).path("status").asString()).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_status FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("ACTIVE");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_attribute_values_aud WHERE product_id = ?",
                Integer.class, productId)).isGreaterThanOrEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variant_attribute_values_aud WHERE product_variant_id = ?",
                Integer.class, redId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variants WHERE product_id = ? AND option_combination_key IS NOT NULL",
                Integer.class, productId)).isEqualTo(2);
    }

    private TypedProductSchema seedTypedProductSchema() {
        UUID typeId = UUID.randomUUID();
        UUID materialDefinitionId = UUID.randomUUID();
        UUID colorDefinitionId = UUID.randomUUID();
        UUID materialAssignmentId = UUID.randomUUID();
        UUID colorAssignmentId = UUID.randomUUID();
        UUID cottonOptionId = UUID.randomUUID();
        UUID linenOptionId = UUID.randomUUID();
        UUID redOptionId = UUID.randomUUID();
        UUID blueOptionId = UUID.randomUUID();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        jdbcTemplate.update("INSERT INTO product_types (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)",
                typeId, "typed-c1-" + unique, "Typed C1 goods", "سلع مصنفة");
        jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'CHOICE')",
                materialDefinitionId, "material-" + unique, "Material", "الخامة");
        jdbcTemplate.update("INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'CHOICE')",
                colorDefinitionId, "color-" + unique, "Color", "اللون");
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                cottonOptionId, materialDefinitionId, "cotton", "Cotton", "قطن", 0);
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                linenOptionId, materialDefinitionId, "linen", "Linen", "كتان", 1);
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                redOptionId, colorDefinitionId, "red", "Red", "أحمر", 0);
        jdbcTemplate.update("INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                blueOptionId, colorDefinitionId, "blue", "Blue", "أزرق", 1);
        jdbcTemplate.update("INSERT INTO product_type_attribute_assignments "
                        + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                        + "VALUES (?, ?, ?, 'PRODUCT', TRUE, 0)",
                materialAssignmentId, typeId, materialDefinitionId);
        jdbcTemplate.update("INSERT INTO product_type_attribute_assignments "
                        + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                        + "VALUES (?, ?, ?, 'VARIANT_SELECTION', TRUE, 0)",
                colorAssignmentId, typeId, colorDefinitionId);
        return new TypedProductSchema(typeId, materialAssignmentId, colorAssignmentId,
                cottonOptionId, linenOptionId, redOptionId, blueOptionId);
    }

    private record TypedProductSchema(UUID productTypeId, UUID materialAssignmentId, UUID colorAssignmentId,
                                      UUID cottonOptionId, UUID linenOptionId, UUID redOptionId, UUID blueOptionId) {
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

    /** The seller's user row id — the providerId the wire must echo (the /me projection's own row). */
    private String sellerId(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", String.class, username);
    }

    private UUID registerProduct(String sellerToken) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-variants","title":"Journey variant product",
                 "description":"ADR-0012 journey stock","priceMinor":149900,"currency":"SAR"}""");
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Variant Journey Gate Integration Test Client");
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

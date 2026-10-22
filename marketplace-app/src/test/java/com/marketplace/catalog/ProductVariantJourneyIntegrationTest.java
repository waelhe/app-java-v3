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
import java.util.List;
import java.util.Map;
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
                 "productTypeId":"%s"}
                """.formatted(schema.productTypeId());
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerGate.accessToken(), createBody);
        assertThat(created.statusCode()).as("typed product create: %s", body(created)).isEqualTo(201);
        JsonNode productBody = objectMapper.readTree(created.body());
        UUID productId = UUID.fromString(productBody.path("id").asString());
        assertThat(productBody.path("productTypeId").asString()).isEqualTo(schema.productTypeId().toString());
        assertThat(productBody.path("status").asString()).isEqualTo("DRAFT");

        HttpResponse<String> untypedCartAttemptWhileDraft = postJson(
                "/api/v1/me/cart/items", sellerGate.accessToken(), """
                        {"productId":"%s","quantity":1}
                        """.formatted(productId));
        assertThat(untypedCartAttemptWhileDraft.statusCode())
                .as("a draft must not enter a product-level cart line: %s", body(untypedCartAttemptWhileDraft))
                .isEqualTo(409);

        HttpResponse<String> initialAttributes = getWithBearer(
                "/api/v1/store/products/" + productId + "/attributes", sellerGate.accessToken());
        assertThat(initialAttributes.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(initialAttributes.body()).size())
                .as("draft creation does not require all schema fields up front").isZero();

        // Required product attributes may be completed while the listing is a draft,
        // but publish must fail closed until the required value has been stored.
        HttpResponse<String> publishWithoutRequiredValue = postJson(
                "/api/v1/store/products/" + productId + "/publish", sellerGate.accessToken(), "");
        assertThat(publishWithoutRequiredValue.statusCode())
                .as("a draft missing a required product attribute cannot publish: %s", body(publishWithoutRequiredValue))
                .isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_status FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("DRAFT");

        HttpResponse<String> cottonValue = postJsonOrPut("/api/v1/store/products/" + productId + "/attributes",
                sellerGate.accessToken(), """
                        [{"assignmentId":"%s","optionId":"%s"}]
                        """.formatted(schema.materialAssignmentId(), schema.cottonOptionId()), "PUT");
        assertThat(cottonValue.statusCode()).as("add the required product attribute: %s", body(cottonValue)).isEqualTo(200);
        assertThat(objectMapper.readTree(cottonValue.body()).get(0).path("optionId").asString())
                .isEqualTo(schema.cottonOptionId().toString());

        // Replace the value and edit the product before publication.
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

        // A type with buyer-selectable dimensions cannot be published until
        // at least one valid typed variant exists.
        HttpResponse<String> earlyPublish = postJson("/api/v1/store/products/" + productId + "/publish",
                sellerGate.accessToken(), "");
        assertThat(earlyPublish.statusCode())
                .as("a ProductType with selectable variants needs at least one active typed variant: %s", body(earlyPublish))
                .isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT product_status FROM products WHERE id = ?", String.class, productId))
                .isEqualTo("DRAFT");

        // Every selection dimension is required for every variant; do not let
        // a missing option generate a hash for the empty combination.
        HttpResponse<String> missingSelection = postJson("/api/v1/store/products/" + productId + "/variants",
                sellerGate.accessToken(), """
                        {"sku":"C1-TYPED-NO-COLOR","name":"No color","priceMinor":110000,"attributeValues":[]}
                        """);
        assertThat(missingSelection.statusCode())
                .as("a selectable variant without its required option must be refused: %s", body(missingSelection))
                .isEqualTo(409);

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

        // A ProductType with buyer-selectable dimensions may only be bought
        // through a chosen typed variant; the product-level cart route refuses it.
        String buyerName = "it-typed-c1-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName);
        GateResult buyerGate = loginGate(buyerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", buyerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> directProductCart = postJson("/api/v1/me/cart/items", buyerGate.accessToken(), """
                {"productId":"%s","quantity":1}
                """.formatted(productId));
        assertThat(directProductCart.statusCode())
                .as("typed selections require a variant id on the cart line: %s", body(directProductCart))
                .isEqualTo(409);

        setVariantStock(sellerGate.accessToken(), redId, 5);
        HttpResponse<String> variantCart = postJson("/api/v1/me/cart/items", buyerGate.accessToken(), """
                {"productId":"%s","variantId":"%s","quantity":1}
                """.formatted(productId, redId));
        assertThat(variantCart.statusCode()).as("selected variant cart add: %s", body(variantCart)).isEqualTo(201);
        JsonNode variantCartLine = objectMapper.readTree(variantCart.body());
        assertThat(variantCartLine.path("variantId").asString()).isEqualTo(redId.toString());
        assertThat(variantCartLine.path("unitAmountMinor").asLong()).isEqualTo(110000L);

        HttpResponse<String> placed = postJson("/api/v1/orders", buyerGate.accessToken(), "");
        assertThat(placed.statusCode()).as("typed variant order placement: %s", body(placed)).isEqualTo(201);
        JsonNode placedOrder = objectMapper.readTree(placed.body());
        assertThat(placedOrder.path("totalAmountMinor").asLong()).isEqualTo(110000L);
        assertThat(placedOrder.path("items").size()).isEqualTo(1);
        assertThat(placedOrder.path("items").get(0).path("variantId").asString()).isEqualTo(redId.toString());
        assertThat(placedOrder.path("items").get(0).path("unitAmountMinor").asLong()).isEqualTo(110000L);

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

    @Test
    void theProductAdminLifecycleIsExposedThroughGraphQlAgainstTheRealCatalogUseCases() throws Exception {
        String sellerName = "it-graphql-product-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);

        // A ProductType with no required assignments exercises the common
        // draft/edit/publish lifecycle; attribute/variant-required publication
        // remains governed by ProductService and has its own typed HTTP journey.
        UUID productTypeId = UUID.randomUUID();
        String codeSuffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbcTemplate.update(
                "INSERT INTO product_types (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)",
                productTypeId, "graphql-catalog-" + codeSuffix, "GraphQL catalog goods", "سلع GraphQL");

        HttpResponse<String> anonymous = postJsonWithoutBearer("/graphql", objectMapper.writeValueAsString(Map.of(
                "query", "query { storeProducts(first: 1) { edges { node { id } } } }")));
        // The pre-login gate's unified contract: the anonymous POST is
        // answered INSIDE GraphQL (HTTP 200) with the classified AUTHN-001
        // error before dispatch — no data key, nothing leaked (the old
        // blanket 401 died with the gate; the read stays just as closed).
        assertThat(anonymous.statusCode())
                .as("GraphQL catalog reads stay closed to the anonymous caller through the pre-login gate")
                .isEqualTo(200);
        com.fasterxml.jackson.databind.JsonNode anonymousBody = objectMapper.readTree(anonymous.body());
        assertThat(anonymousBody.has("data")).as("no data key may leak").isFalse();
        assertThat(anonymousBody.path("errors").size()).isEqualTo(1);
        assertThat(anonymousBody.path("errors").get(0).path("extensions").path("errorCode").asText())
                .isEqualTo("AUTHN-001");

        String createQuery = """
                mutation CreateStoreProduct($input: StoreProductCreateInput!) {
                  createStoreProduct(input: $input) {
                    id title description priceMinor currency providerId productTypeId status
                  }
                }
                """;
        String originalTitle = "GraphQL dashboard lifecycle " + codeSuffix;
        String createBody = objectMapper.writeValueAsString(Map.of(
                "query", createQuery,
                "variables", Map.of("input", Map.of(
                        "storeCategoryCode", "journey-variants",
                        "title", originalTitle,
                        "description", "Created through the platform GraphQL contract.",
                        "priceMinor", "149900",
                        "currency", "SAR",
                        "productTypeId", productTypeId.toString()))));
        HttpResponse<String> createdResponse =
                postJson("/graphql", sellerGate.accessToken(), createBody);
        assertThat(createdResponse.statusCode())
                .as("GraphQL create transport: %s", body(createdResponse)).isEqualTo(200);
        JsonNode createEnvelope = objectMapper.readTree(createdResponse.body());
        assertThat(createEnvelope.path("errors").isMissingNode())
                .as("createStoreProduct errors: %s", body(createdResponse)).isTrue();
        JsonNode created = createEnvelope.path("data").path("createStoreProduct");
        UUID productId = UUID.fromString(created.path("id").asString());
        assertThat(created.path("title").asString()).isEqualTo(originalTitle);
        assertThat(created.path("priceMinor").asString()).isEqualTo("149900");
        assertThat(created.path("productTypeId").asString()).isEqualTo(productTypeId.toString());
        assertThat(created.path("status").asString()).isEqualTo("DRAFT");

        String updateQuery = """
                mutation UpdateStoreProduct($id: ID!, $input: StoreProductUpdateInput!) {
                  updateStoreProduct(id: $id, input: $input) {
                    id title description priceMinor currency productTypeId status
                  }
                }
                """;
        String editedTitle = originalTitle + " — edited";
        String updatedBody = objectMapper.writeValueAsString(Map.of(
                "query", updateQuery,
                "variables", Map.of(
                        "id", productId.toString(),
                        "input", Map.of(
                                "title", editedTitle,
                                "description", "Updated through GraphQL; read-back must match.",
                                "priceMinor", "128999"))));
        HttpResponse<String> updatedResponse =
                postJson("/graphql", sellerGate.accessToken(), updatedBody);
        assertThat(updatedResponse.statusCode())
                .as("GraphQL update transport: %s", body(updatedResponse)).isEqualTo(200);
        JsonNode updateEnvelope = objectMapper.readTree(updatedResponse.body());
        assertThat(updateEnvelope.path("errors").isMissingNode())
                .as("updateStoreProduct errors: %s", body(updatedResponse)).isTrue();
        assertThat(updateEnvelope.path("data").path("updateStoreProduct").path("status").asString())
                .isEqualTo("DRAFT");

        String publishQuery = """
                mutation PublishStoreProduct($id: ID!) {
                  publishStoreProduct(id: $id) {
                    id title description priceMinor currency productTypeId status
                  }
                }
                """;
        String publishBody = objectMapper.writeValueAsString(Map.of(
                "query", publishQuery,
                "variables", Map.of("id", productId.toString())));
        HttpResponse<String> publishedResponse =
                postJson("/graphql", sellerGate.accessToken(), publishBody);
        assertThat(publishedResponse.statusCode())
                .as("GraphQL publish transport: %s", body(publishedResponse)).isEqualTo(200);
        JsonNode publishEnvelope = objectMapper.readTree(publishedResponse.body());
        assertThat(publishEnvelope.path("errors").isMissingNode())
                .as("publishStoreProduct errors: %s", body(publishedResponse)).isTrue();
        assertThat(publishEnvelope.path("data").path("publishStoreProduct").path("status").asString())
                .isEqualTo("ACTIVE");

        String readQuery = """
                query ReadStoreProduct($id: ID!) {
                  storeProduct(id: $id) {
                    id title description priceMinor currency providerId productTypeId status
                  }
                }
                """;
        String readBody = objectMapper.writeValueAsString(Map.of(
                "query", readQuery, "variables", Map.of("id", productId.toString())));
        HttpResponse<String> readResponse = postJson("/graphql", sellerGate.accessToken(), readBody);
        assertThat(readResponse.statusCode())
                .as("GraphQL read-back transport: %s", body(readResponse)).isEqualTo(200);
        JsonNode readEnvelope = objectMapper.readTree(readResponse.body());
        assertThat(readEnvelope.path("errors").isMissingNode())
                .as("storeProduct read-back errors: %s", body(readResponse)).isTrue();
        JsonNode readBack = readEnvelope.path("data").path("storeProduct");
        assertThat(readBack.path("id").asString()).isEqualTo(productId.toString());
        assertThat(readBack.path("title").asString()).isEqualTo(editedTitle);
        assertThat(readBack.path("description").asString())
                .isEqualTo("Updated through GraphQL; read-back must match.");
        assertThat(readBack.path("priceMinor").asString()).isEqualTo("128999");
        assertThat(readBack.path("currency").asString()).isEqualTo("SAR");
        assertThat(readBack.path("providerId").asString()).isEqualTo(sellerId(sellerName));
        assertThat(readBack.path("productTypeId").asString()).isEqualTo(productTypeId.toString());
        assertThat(readBack.path("status").asString()).isEqualTo("ACTIVE");

        // An ID is not authorization: a second provider must not read or edit
        // another seller's product through the GraphQL adapter.
        String strangerName = "it-graphql-product-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(strangerName, "USER", "PROVIDER");
        GateResult strangerGate = loginGate(strangerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", strangerGate.accessToken()).statusCode()).isEqualTo(200);

        HttpResponse<String> strangerReadResponse = postJson("/graphql", strangerGate.accessToken(), readBody);
        assertThat(strangerReadResponse.statusCode()).isEqualTo(200);
        JsonNode strangerReadEnvelope = objectMapper.readTree(strangerReadResponse.body());
        assertThat(strangerReadEnvelope.path("errors").isArray())
                .as("a stranger's owned-product read returns a field-level GraphQL error").isTrue();
        assertThat(strangerReadEnvelope.path("data").path("storeProduct").isNull())
                .as("a stranger must not receive the other provider's product").isTrue();

        String strangerUpdateBody = objectMapper.writeValueAsString(Map.of(
                "query", updateQuery,
                "variables", Map.of(
                        "id", productId.toString(),
                        "input", Map.of(
                                "title", "forged cross-provider edit",
                                "description", "This update must be denied.",
                                "priceMinor", "1"))));
        HttpResponse<String> strangerUpdateResponse =
                postJson("/graphql", strangerGate.accessToken(), strangerUpdateBody);
        assertThat(strangerUpdateResponse.statusCode()).isEqualTo(200);
        JsonNode strangerUpdateEnvelope = objectMapper.readTree(strangerUpdateResponse.body());
        assertThat(strangerUpdateEnvelope.path("errors").isArray())
                .as("cross-provider mutation must fail at the owning use case").isTrue();

        String listQuery = """
                query StoreProductRoster {
                  storeProducts(first: 2) {
                    edges { node { id title status } }
                    pageInfo { hasNextPage endCursor }
                  }
                  storeProductTypes { id code nameEn nameAr description }
                }
                """;
        HttpResponse<String> listResponse = postJson("/graphql", sellerGate.accessToken(),
                objectMapper.writeValueAsString(Map.of("query", listQuery)));
        assertThat(listResponse.statusCode())
                .as("GraphQL connection and ProductType roster: %s", body(listResponse)).isEqualTo(200);
        JsonNode listEnvelope = objectMapper.readTree(listResponse.body());
        assertThat(listEnvelope.path("errors").isMissingNode())
                .as("storeProducts/ProductType errors: %s", body(listResponse)).isTrue();
        assertThat(listEnvelope.path("data").path("storeProducts").path("edges").size())
                .isBetween(1, 2);
        assertThat(listEnvelope.path("data").path("storeProductTypes").toString())
                .contains(productTypeId.toString());
    }

    @Test
    void theGraphQlTypedAttributeAndVariantJourneyEnforcesRequiredValuesAndUniqueSelections() throws Exception {
        String sellerName = "it-graphql-typed-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);

        UUID productTypeId = UUID.randomUUID();
        UUID materialDefinitionId = UUID.randomUUID();
        UUID colorDefinitionId = UUID.randomUUID();
        UUID materialAssignmentId = UUID.randomUUID();
        UUID colorAssignmentId = UUID.randomUUID();
        UUID redOptionId = UUID.randomUUID();
        UUID blueOptionId = UUID.randomUUID();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);

        jdbcTemplate.update(
                "INSERT INTO product_types (id, code, name_en, name_ar) VALUES (?, ?, ?, ?)",
                productTypeId, "graphql-typed-" + suffix, "GraphQL typed goods", "سلع GraphQL معرفة");
        jdbcTemplate.update(
                "INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'TEXT')",
                materialDefinitionId, "graphql-material-" + suffix, "Material", "الخامة");
        jdbcTemplate.update(
                "INSERT INTO catalog_attribute_definitions (id, code, name_en, name_ar, value_type) "
                        + "VALUES (?, ?, ?, ?, 'CHOICE')",
                colorDefinitionId, "graphql-color-" + suffix, "Color", "اللون");
        jdbcTemplate.update(
                "INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                redOptionId, colorDefinitionId, "red", "Red", "أحمر", 0);
        jdbcTemplate.update(
                "INSERT INTO catalog_attribute_options "
                        + "(id, attribute_definition_id, code, name_en, name_ar, position) VALUES (?, ?, ?, ?, ?, ?)",
                blueOptionId, colorDefinitionId, "blue", "Blue", "أزرق", 1);
        jdbcTemplate.update(
                "INSERT INTO product_type_attribute_assignments "
                        + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                        + "VALUES (?, ?, ?, 'PRODUCT', TRUE, 0)",
                materialAssignmentId, productTypeId, materialDefinitionId);
        jdbcTemplate.update(
                "INSERT INTO product_type_attribute_assignments "
                        + "(id, product_type_id, attribute_definition_id, scope, is_required, position) "
                        + "VALUES (?, ?, ?, 'VARIANT_SELECTION', TRUE, 0)",
                colorAssignmentId, productTypeId, colorDefinitionId);

        String configQuery = """
                query StoreProductTypes {
                  storeProductTypes {
                    id code
                    attributes {
                      assignmentId attributeDefinitionId code valueType scope required
                      options { id code nameEn nameAr position }
                    }
                  }
                }
                """;
        HttpResponse<String> configResponse = postJson("/graphql", sellerGate.accessToken(),
                objectMapper.writeValueAsString(Map.of("query", configQuery)));
        assertThat(configResponse.statusCode()).isEqualTo(200);
        JsonNode configEnvelope = objectMapper.readTree(configResponse.body());
        assertThat(configEnvelope.path("errors").isMissingNode())
                .as("typed ProductType configuration errors: %s", body(configResponse)).isTrue();
        JsonNode configuredType = null;
        for (JsonNode candidate : configEnvelope.path("data").path("storeProductTypes")) {
            if (productTypeId.toString().equals(candidate.path("id").asString())) {
                configuredType = candidate;
                break;
            }
        }
        assertThat(configuredType).isNotNull();
        assertThat(configuredType.path("attributes").size()).isEqualTo(2);
        assertThat(configuredType.toString()).contains(materialAssignmentId.toString(), colorAssignmentId.toString(),
                redOptionId.toString(), blueOptionId.toString());

        String createQuery = """
                mutation CreateTypedProduct($input: StoreProductCreateInput!) {
                  createStoreProduct(input: $input) { id status productTypeId }
                }
                """;
        String createBody = objectMapper.writeValueAsString(Map.of(
                "query", createQuery,
                "variables", Map.of("input", Map.of(
                        "storeCategoryCode", "journey-variants",
                        "title", "GraphQL typed journey " + suffix,
                        "description", "Required attributes and selectable variants.",
                        "priceMinor", "19900",
                        "currency", "SAR",
                        "productTypeId", productTypeId.toString()))));
        HttpResponse<String> createResponse = postJson("/graphql", sellerGate.accessToken(), createBody);
        assertThat(createResponse.statusCode()).isEqualTo(200);
        JsonNode createEnvelope = objectMapper.readTree(createResponse.body());
        assertThat(createEnvelope.path("errors").isMissingNode())
                .as("create typed product errors: %s", body(createResponse)).isTrue();
        JsonNode createdProduct = createEnvelope.path("data").path("createStoreProduct");
        UUID productId = UUID.fromString(createdProduct.path("id").asString());
        assertThat(createdProduct.path("status").asString()).isEqualTo("DRAFT");

        // Save the required PRODUCT-scope value through the GraphQL mutation.
        String replaceAttributesQuery = """
                mutation ReplaceProductAttributes($id: ID!, $values: [StoreProductAttributeValueInput!]!) {
                  replaceStoreProductAttributes(id: $id, values: $values) {
                    assignmentId textValue numberValue booleanValue optionId
                  }
                }
                """;
        String replaceAttributesBody = objectMapper.writeValueAsString(Map.of(
                "query", replaceAttributesQuery,
                "variables", Map.of(
                        "id", productId.toString(),
                        "values", List.of(Map.of(
                                "assignmentId", materialAssignmentId.toString(),
                                "textValue", "linen")))));
        HttpResponse<String> replaceResponse =
                postJson("/graphql", sellerGate.accessToken(), replaceAttributesBody);
        assertThat(replaceResponse.statusCode()).isEqualTo(200);
        JsonNode replaceEnvelope = objectMapper.readTree(replaceResponse.body());
        assertThat(replaceEnvelope.path("errors").isMissingNode())
                .as("replace product attributes errors: %s", body(replaceResponse)).isTrue();
        assertThat(replaceEnvelope.path("data").path("replaceStoreProductAttributes").get(0).path("textValue").asString())
                .isEqualTo("linen");

        // VARIANT_SELECTION is required and the option must belong to that assignment's CHOICE definition.
        String createVariantQuery = """
                mutation CreateTypedVariant($productId: ID!, $input: StoreProductVariantCreateInput!) {
                  createStoreProductVariant(productId: $productId, input: $input) {
                    id productId sku priceMinor currency status stockQuantity
                  }
                }
                """;
        String sku = "GRAPHQL-TYPED-" + suffix.toUpperCase();
        String createVariantBody = objectMapper.writeValueAsString(Map.of(
                "query", createVariantQuery,
                "variables", Map.of(
                        "productId", productId.toString(),
                        "input", Map.of(
                                "sku", sku,
                                "name", "Red / typed",
                                "priceMinor", "129900",
                                "attributeValues", List.of(Map.of(
                                        "assignmentId", colorAssignmentId.toString(),
                                        "optionId", redOptionId.toString()))))));
        HttpResponse<String> variantResponse =
                postJson("/graphql", sellerGate.accessToken(), createVariantBody);
        assertThat(variantResponse.statusCode()).isEqualTo(200);
        JsonNode variantEnvelope = objectMapper.readTree(variantResponse.body());
        assertThat(variantEnvelope.path("errors").isMissingNode())
                .as("create typed variant errors: %s", body(variantResponse)).isTrue();
        JsonNode createdVariant = variantEnvelope.path("data").path("createStoreProductVariant");
        UUID variantId = UUID.fromString(createdVariant.path("id").asString());
        assertThat(createdVariant.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(createdVariant.path("priceMinor").asString()).isEqualTo("129900");

        // The same option combination is not allowed twice, even with a different SKU.
        String duplicateBody = objectMapper.writeValueAsString(Map.of(
                "query", createVariantQuery,
                "variables", Map.of(
                        "productId", productId.toString(),
                        "input", Map.of(
                                "sku", "GRAPHQL-DUPLICATE-" + suffix.toUpperCase(),
                                "name", "Duplicate red",
                                "priceMinor", "130000",
                                "attributeValues", List.of(Map.of(
                                        "assignmentId", colorAssignmentId.toString(),
                                        "optionId", redOptionId.toString()))))));
        HttpResponse<String> duplicateResponse =
                postJson("/graphql", sellerGate.accessToken(), duplicateBody);
        assertThat(duplicateResponse.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(duplicateResponse.body()).path("errors").isArray())
                .as("duplicate selection must return a classified GraphQL error").isTrue();

        String publishQuery = """
                mutation PublishTypedProduct($id: ID!) {
                  publishStoreProduct(id: $id) { id status }
                }
                """;
        HttpResponse<String> publishResponse = postJson("/graphql", sellerGate.accessToken(),
                objectMapper.writeValueAsString(Map.of(
                        "query", publishQuery, "variables", Map.of("id", productId.toString()))));
        assertThat(publishResponse.statusCode()).isEqualTo(200);
        JsonNode publishEnvelope = objectMapper.readTree(publishResponse.body());
        assertThat(publishEnvelope.path("errors").isMissingNode())
                .as("publish typed product errors: %s", body(publishResponse)).isTrue();
        assertThat(publishEnvelope.path("data").path("publishStoreProduct").path("status").asString())
                .isEqualTo("ACTIVE");

        String readTypedStateQuery = """
                query TypedProductReadBack($productId: ID!, $variantId: ID!) {
                  storeProductAttributeValues(productId: $productId) {
                    assignmentId textValue numberValue booleanValue optionId
                  }
                  storeProductVariants(productId: $productId) {
                    id sku priceMinor status
                  }
                  storeProductVariantAttributeValues(variantId: $variantId) {
                    assignmentId textValue numberValue booleanValue optionId
                  }
                }
                """;
        HttpResponse<String> stateResponse = postJson("/graphql", sellerGate.accessToken(),
                objectMapper.writeValueAsString(Map.of(
                        "query", readTypedStateQuery,
                        "variables", Map.of(
                                "productId", productId.toString(),
                                "variantId", variantId.toString()))));
        assertThat(stateResponse.statusCode()).isEqualTo(200);
        JsonNode stateEnvelope = objectMapper.readTree(stateResponse.body());
        assertThat(stateEnvelope.path("errors").isMissingNode())
                .as("typed read-back errors: %s", body(stateResponse)).isTrue();
        JsonNode productValues = stateEnvelope.path("data").path("storeProductAttributeValues");
        assertThat(productValues.size()).isEqualTo(1);
        assertThat(productValues.get(0).path("assignmentId").asString()).isEqualTo(materialAssignmentId.toString());
        assertThat(productValues.get(0).path("textValue").asString()).isEqualTo("linen");
        JsonNode variantValues = stateEnvelope.path("data").path("storeProductVariantAttributeValues");
        assertThat(variantValues.size()).isEqualTo(1);
        assertThat(variantValues.get(0).path("assignmentId").asString()).isEqualTo(colorAssignmentId.toString());
        assertThat(variantValues.get(0).path("optionId").asString()).isEqualTo(redOptionId.toString());
        JsonNode variants = stateEnvelope.path("data").path("storeProductVariants");
        assertThat(variants.size()).isEqualTo(1);
        assertThat(variants.get(0).path("sku").asString()).isEqualTo(sku);
        assertThat(variants.get(0).path("status").asString()).isEqualTo("ACTIVE");
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

    private void setVariantStock(String sellerToken, UUID variantId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/variants/" + variantId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("variant stock write: %s", body(stocked)).isEqualTo(200);
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

    private HttpResponse<String> postJsonWithoutBearer(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
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

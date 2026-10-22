package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductType;
import com.marketplace.catalog.ProductTypeRepository;
import com.marketplace.catalog.StoreProductStatus;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import test.config.IntegrationContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b4-c):
 * end-to-end proof of the Saleor Products-domain {@code product} and
 * {@code products} read roots through the REAL SDL, the REAL DGS
 * wiring, the REAL security chain (the mocked JwtDecoder stamps the
 * caller's subject and roles claim exactly the way the production
 * converter reads them), the REAL catalog owner (no SPI mocking: the
 * REAL SQL keyset scroll and the permission-aware population execute
 * against the real PostgreSQL over the SAME authoritative products
 * table the legacy platform roots read), and the REAL typed
 * global-ID codec.
 *
 * <p>The acceptance legs mirror the packet's gate: the
 * PERMISSION-AWARE population (the public caller's list answers ONLY
 * the ACTIVE lifecycle slice with its own count, and the public
 * caller's lookup of an unpublished row answers the safe null — the
 * data-scope negative; the ADMIN caller's list includes the
 * unpublished states with the whole population's count, and its
 * lookup reads them — the include-unpublished mapping), the
 * forward-only keyset scroll with cursor follow-through, the
 * round-trip global-ID stability, the guessed-ID null, the
 * malformed/raw/mistyped-ID VAL-001 legs, the anonymous rejection
 * (the reopened pre-login gate contract — the classified AUTHN-001
 * GraphQL error inside the envelope, no data), the schema-less
 * product's honest null productType, the empty page, and the
 * classified errors for the declared-but-unexecuted arguments
 * (filter/where/search/channel/attribute-sort/unowned sort fields/
 * before/last/slug/externalReference/keyless — never silent
 * no-ops).</p>
 *
 * <p>The real migrated schema, not Hibernate's ddl-auto approximation
 * (the AttributeGraphQlIntegrationTest precedent): the scroll and the
 * seeded rows run against the Flyway V1..V247 schema — the honest
 * standing shape (the seed migration's own rows are part of the
 * population the assertions reason about relatively, never by
 * absolute count).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductGraphQlIntegrationTest {

    private static final String CONSUMER_SUBJECT = "p2-prods-consumer-subject";
    private static final String ADMIN_SUBJECT = "p2-prods-admin-subject";


    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private UUID providerId;
    private String storeCategoryCode;
    private UUID seededTypeId;
    private final List<UUID> seededProductIds = new ArrayList<>();

    @BeforeEach
    void seedReferenceRows() {
        providerId = UUID.randomUUID();
        userRepository.save(new User(providerId, providerId.toString(), null,
                "b4c test provider", com.marketplace.identity.UserRole.PROVIDER));

        storeCategoryCode = "b4c-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), storeCategoryCode, "b4c test", "اختبار المنتجات");

        // The typed DRAFT product needs a REAL goods schema (the validated
        // FK), and the untyped ACTIVE products keep the legacy shape.
        ProductType type = ProductType.create("b4c-type-" + UUID.randomUUID().toString().substring(0, 8),
                "b4c Widget Schema", null, null);
        productTypeRepository.save(type);
        seededTypeId = type.getId();

        persistActive("b4c Alpha Widget");
        persistActive("b4c Beta Widget");
        persistActive("b4c Gamma Widget");
        persistDraft("b4c Unpublished Draft Widget");
        persistSuspended("b4c Suspended Widget");
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededProductIds.isEmpty()) {
            productRepository.deleteAllById(new ArrayList<>(seededProductIds));
            seededProductIds.clear();
        }
        if (seededTypeId != null) {
            productTypeRepository.deleteById(seededTypeId);
            seededTypeId = null;
        }
        jdbcTemplate.update("DELETE FROM store_categories WHERE code = ?", storeCategoryCode);
        userRepository.deleteById(providerId);
    }

    private void persistActive(String title) {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "b4c fixture", 1000L, "SAR", providerId));
        seededProductIds.add(product.getId());
    }

    private void persistDraft(String title) {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "b4c fixture", 1000L, "SAR", providerId, seededTypeId));
        seededProductIds.add(product.getId());
    }

    private void persistSuspended(String title) {
        Product product = Product.register(
                storeCategoryCode, title, "b4c fixture", 1000L, "SAR", providerId);
        product.suspend();
        productRepository.save(product);
        seededProductIds.add(product.getId());
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (authorities claim "roles", prefix ROLE_).
     */
    private HttpGraphQlTester testerFor(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader("Authorization", "Bearer test-token"))
                .build();
    }

    /**
     * Decodes an emitted typed global ID — the honest tag/UUID verification
     * (Base64("Tag:") prefix compares can never hold: the padded '==' of the
     * tag-only encoding is not part of the full "Tag:<uuid>" encoding; the
     * decoded form is checked against the plain tag and the native UUID
     * shape instead).
     */
    private static String decodedGlobalId(String globalId) {
        String decoded = new String(java.util.Base64.getDecoder().decode(globalId),
                java.nio.charset.StandardCharsets.UTF_8);
        int separator = decoded.indexOf(':');
        assertThat(separator).isPositive();
        UUID.fromString(decoded.substring(separator + 1));
        return decoded;
    }

    /** A well-formed typed global ID for the Product type over any native UUID. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String categoryGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // products — the permission-aware population's SQL keyset scroll
    // ------------------------------------------------------------------

    @Test
    void consumerSeesOnlyTheActivePopulationWithItsOwnCount() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 10, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name productType status } }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges[*].node.status").entityList(String.class)
                .satisfies(values -> assertThat(values).containsOnly("ACTIVE"))
                .path("products.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).anySatisfy(name -> assertThat(name).startsWith("b4c ")))
                .path("products.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3));
    }

    @Test
    void adminSeesTheIncludeUnpublishedPopulationWithTheWholeCount() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { products(first: 50, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name status } }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges[*].node.status").entityList(String.class)
                .satisfies(c -> assertThat(c).containsAnyOf("DRAFT", "SUSPENDED"))
                .path("products.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names)
                        .anySatisfy(name -> assertThat(name).isEqualTo("b4c Unpublished Draft Widget")));
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String firstPageEndCursor = tester.document("""
                        query { products(first: 2, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges").entityList(Object.class).hasSize(2)
                .path("products.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("products.pageInfo.endCursor").entity(String.class).get();

        String firstPageLastName = tester.document("""
                        query { products(first: 2, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges[1].node.name").entity(String.class).get();

        tester.document("""
                        query($after: String) { products(first: 2, after: $after, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name } }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .errors().verify()
                .path("products.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).doesNotContain(firstPageLastName));
    }

    @Test
    void schemalessProductAnswersTheHonestNullProductType() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name productType } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges[0].node.name").entity(String.class).isEqualTo("b4c Alpha Widget")
                .path("products.edges[0].node.productType").valueIsNull();
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 0) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges").entityList(Object.class).hasSize(0)
                .path("products.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("products.totalCount").valueIsNull();
    }

    // ------------------------------------------------------------------
    // product — the permission-aware bounded lookup
    // ------------------------------------------------------------------

    @Test
    void productRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String emittedId = tester.document("""
                        query { products(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("products.edges[0].node.id").entity(String.class).get();

        assertThat(decodedGlobalId(emittedId)).startsWith("Product:");
        tester.document("""
                        query($id: ID!) { product(id: $id) { id name } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("product.id").entity(String.class).isEqualTo(emittedId)
                .path("product.name").entity(String.class).isEqualTo("b4c Alpha Widget");
    }

    @Test
    void consumerLookupOfAnUnpublishedRowAnswersTheSafeNull() {
        // The data-scope negative: the DRAFT row EXISTS (the ADMIN leg
        // below reads it) but the public caller's lookup answers the
        // safe null — never an error and never a leak.
        UUID draftId = seededProductIds.stream()
                .filter(id -> productRepository.findById(id)
                        .map(p -> p.getStatus() == StoreProductStatus.DRAFT)
                        .orElse(false))
                .findFirst().orElseThrow();
        assertThat(draftId).isNotNull();

        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", productGlobalId(draftId))
                .execute()
                .errors().verify()
                .path("product").valueIsNull();

        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        query($id: ID!) { product(id: $id) { id status } }
                        """)
                .variable("id", productGlobalId(draftId))
                .execute()
                .errors().verify()
                .path("product.status").entity(String.class).isEqualTo("DRAFT");
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors().verify()
                .path("product").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the typed global-ID codec's classified validation errors
    // ------------------------------------------------------------------

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id")
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void mistypedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", categoryGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the anonymous boundary — the reopened pre-login gate contract
    // ------------------------------------------------------------------

    @Test
    void anonymousProductQueryIsAnsweredByThePreLoginGateEnvelope() {
        // No Authorization header: the security chain now permits the
        // anonymous POST and PreLoginGraphQlGate is the wall — the gate
        // parses the document, finds the protected `products` root, and
        // answers the classified AUTHN-001 GraphQL error INSIDE the
        // envelope (HTTP 200 + application/json, the error before any
        // dispatch). The same reopened contract GraphQlSecurityIntegration
        // Test pins at the surface level, asserted here for the products
        // root's own journey: the classified error pair, and NO data —
        // the protected collection never resolves for the anonymous
        // caller.
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ products(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.errors[0].message")
                .isEqualTo("Authentication is required for this GraphQL operation")
                .jsonPath("$.data").doesNotExist();
    }

    // ------------------------------------------------------------------
    // the classified not-implemented boundaries — never silent no-ops
    // ------------------------------------------------------------------

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 2, filter: { search: "x" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("filter is not implemented")));
    }

    @Test
    void whereFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 2, where: { ids: ["x"] }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("where filter is not implemented")));
    }

    @Test
    void searchAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 2, search: "widget") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("search is not implemented")));
    }

    @Test
    void channelAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 2, channel: "channel-1") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("channel filtering is not implemented")));
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("forward-only")));
    }

    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { products(first: 2, sortBy: { direction: ASC, field: PRICE }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("PRICE is not implemented")));
    }

    @Test
    void slugLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($slug: String) { product(slug: $slug) { id } }
                        """)
                .variable("slug", "widget")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("slug lookup is not implemented")));
    }

    @Test
    void externalReferenceLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($externalReference: String) { product(externalReference: $externalReference) { id } }
                        """)
                .variable("externalReference", "erp-widget-1")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("externalReference lookup is not implemented")));
    }

    @Test
    void keylessLookupAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { product { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> {
                            assertThat(error.getMessage()).contains("product requires its id");
                            assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                        }));
    }

    // ------------------------------------------------------------------
    // the product mutation roots — the real provider-scoped write stack
    // (agent-2): the owning provider IS the caller — the seeded provider
    // user's subject rides the JWT exactly as IdentityUserProvider
    // resolves it.
    // ------------------------------------------------------------------

    @Test
    void providerCreatesAProductThroughTheRealMutationStack() {
        HttpGraphQlTester providerTester = testerFor(providerId.toString(), "PROVIDER");

        String emittedId = providerTester.document("""
                        mutation { productCreate(input: {name: "P2 Created Widget", description: "P2 fixture",
                                storeCategoryCode: "%s", priceMinor: 1250, currency: "SAR"}) {
                            errors { field code }
                            product { id name status }
                        } }
                        """.formatted(storeCategoryCode))
                .execute()
                .errors().verify()
                .path("productCreate.errors").entityList(Object.class).hasSize(0)
                .path("productCreate.product.id").entity(String.class).get();

        String decoded = new String(java.util.Base64.getDecoder().decode(emittedId),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(decoded).startsWith("Product:");
        UUID rowId = UUID.fromString(decoded.substring("Product:".length()));
        seededProductIds.add(rowId);

        // The real-stack read-back: the row persisted with the caller as
        // its owning provider and the untyped register's ACTIVE start.
        Product row = productRepository.findById(rowId).orElseThrow();
        assertThat(row.getTitle()).isEqualTo("P2 Created Widget");
        assertThat(row.getProviderId()).isEqualTo(providerId);
        assertThat(row.getPriceMinor()).isEqualTo(1250L);
        assertThat(row.getStatus()).isEqualTo(com.marketplace.catalog.StoreProductStatus.ACTIVE);

        providerTester.document("""
                        query($id: ID!) { product(id: $id) { name status } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("product.name").entity(String.class).isEqualTo("P2 Created Widget")
                .path("product.status").entity(String.class).isEqualTo("ACTIVE");
    }

    @Test
    void unknownStoreCategoryCreateAnswersThePayloadError() {
        HttpGraphQlTester providerTester = testerFor(providerId.toString(), "PROVIDER");

        providerTester.document("""
                        mutation { productCreate(input: {name: "P2 Missing Category", storeCategoryCode: "missing-cat",
                                priceMinor: 1250, currency: "SAR"}) {
                            errors { field code }
                            product { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productCreate.errors[0].field").entity(String.class).isEqualTo("storeCategoryCode")
                .path("productCreate.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("productCreate.product").valueIsNull();
    }

    @Test
    void adminIsDeniedTheProductCreateByTheOwningUseCase() {
        // The owning use case enforces the PROVIDER capability for
        // provider-owned rows — the platform's two-sided ownership law
        // (the recorded RBAC divergence), proven by the real stack.
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productCreate(input: {name: "P2 Admin Deny", storeCategoryCode: "%s",
                                priceMinor: 1250, currency: "SAR"}) {
                            errors { code }
                            product { id }
                        } }
                        """.formatted(storeCategoryCode))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                });
    }

    @Test
    void providerUpdatesAProductThroughTheRealMutationStack() {
        HttpGraphQlTester providerTester = testerFor(providerId.toString(), "PROVIDER");
        UUID rowId = seededProductIds.get(0);

        providerTester.document("""
                        mutation { productUpdate(id: "%s", input: {name: "P2 Updated Widget", priceMinor: 2000}) {
                            errors { code }
                            product { name status }
                        } }
                        """.formatted(productGlobalId(rowId)))
                .execute()
                .errors().verify()
                .path("productUpdate.errors").entityList(Object.class).hasSize(0)
                .path("productUpdate.product.name").entity(String.class).isEqualTo("P2 Updated Widget");

        // The partial-preserve law: the description column is untouched
        // by the name+price command.
        Product row = productRepository.findById(rowId).orElseThrow();
        assertThat(row.getTitle()).isEqualTo("P2 Updated Widget");
        assertThat(row.getPriceMinor()).isEqualTo(2000L);
        assertThat(row.getDescription()).isEqualTo("b4c fixture");
    }

    @Test
    void aStrangersRowAnswersTheSafeAbsenceOnUpdate() {
        // The ownership law's own R5 shape: a row the caller does not
        // own answers the same NOT_FOUND as a missing row — never an
        // existence disclosure. The seeded rows belong to the seeded
        // provider; a DIFFERENT provider's subject gets its own row.
        UUID strangerProviderId = UUID.randomUUID();
        userRepository.save(new User(strangerProviderId, strangerProviderId.toString(), null,
                "p2 stranger provider", com.marketplace.identity.UserRole.PROVIDER));
        try {
            Product strangers = productRepository.save(Product.register(storeCategoryCode,
                    "P2 Stranger Widget", "fixture", 1000L, "SAR", strangerProviderId));
            seededProductIds.add(strangers.getId());

            HttpGraphQlTester providerTester = testerFor(providerId.toString(), "PROVIDER");
            providerTester.document("""
                            mutation { productUpdate(id: "%s", input: {name: "P2 Hijack"}) {
                                errors { field code }
                                product { id }
                            } }
                            """.formatted(productGlobalId(strangers.getId())))
                    .execute()
                    .errors().verify()
                    .path("productUpdate.errors[0].field").entity(String.class).isEqualTo("id")
                    .path("productUpdate.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                    .path("productUpdate.product").valueIsNull();
        } finally {
            userRepository.deleteById(strangerProviderId);
        }
    }

    @Test
    void providerDeletesAndBulkDeletesProductsThroughTheRealMutationStack() {
        HttpGraphQlTester providerTester = testerFor(providerId.toString(), "PROVIDER");
        UUID rowId = seededProductIds.get(0);
        UUID secondRowId = seededProductIds.get(1);

        providerTester.document("""
                        mutation { productDelete(id: "%s") {
                            errors { code }
                            product { name }
                        } }
                        """.formatted(productGlobalId(rowId)))
                .execute()
                .errors().verify()
                .path("productDelete.errors").entityList(Object.class).hasSize(0)
                .path("productDelete.product.name").entity(String.class).isEqualTo("b4c Alpha Widget");

        // The soft-deleted row is absent to the read root (the honest
        // absence, never an error).
        providerTester.document("""
                        query($id: ID!) { product(id: $id) { id } }
                        """)
                .variable("id", productGlobalId(rowId))
                .execute()
                .errors().verify()
                .path("product").valueIsNull();

        providerTester.document("""
                        mutation { productBulkDelete(ids: ["%s"]) {
                            count
                            errors { code }
                        } }
                        """.formatted(productGlobalId(secondRowId)))
                .execute()
                .errors().verify()
                .path("productBulkDelete.count").entity(Integer.class).isEqualTo(1)
                .path("productBulkDelete.errors").entityList(Object.class).hasSize(0);
    }
}

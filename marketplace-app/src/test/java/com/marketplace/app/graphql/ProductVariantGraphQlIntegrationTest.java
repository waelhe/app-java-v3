package com.marketplace.app.graphql;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductType;
import com.marketplace.catalog.ProductTypeRepository;
import com.marketplace.catalog.ProductVariant;
import com.marketplace.catalog.ProductVariantRepository;
import com.marketplace.catalog.StoreProductStatus;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import test.config.IntegrationContainers;
import org.springframework.graphql.ResponseError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
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
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b5-a):
 * end-to-end proof of the Saleor Products-domain {@code productVariant}
 * and {@code productVariants} read roots through the REAL SDL, the REAL
 * DGS wiring, the REAL security chain (the mocked JwtDecoder stamps the
 * caller's subject and roles claim exactly the way the production
 * converter reads them), the REAL catalog owner (no SPI mocking: the
 * REAL SQL keyset scrolls and the two-row-gated permission-aware
 * population execute against the real PostgreSQL over the SAME
 * authoritative product_variants rows the cart's reservation
 * arithmetic writes), and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the packet's gate: the
 * PERMISSION-AWARE population (the public caller's list answers ONLY
 * the two-row-gated population — ACTIVE variants of ACTIVE products —
 * with its own count, and the public caller's lookup of an
 * out-of-population row answers the safe null — BOTH data-scope
 * negatives: the non-ACTIVE variant AND the ACTIVE variant under the
 * non-ACTIVE parent; the ADMIN caller's list includes the unpublished
 * rows with the whole population's count, and its lookups read them —
 * the include-unpublished mapping), the forward-only keyset scroll
 * with cursor follow-through in BOTH pinned sort fields (the
 * LAST_MODIFIED_AT audit-column scroll AND the unique-key ID scroll),
 * the round-trip global-ID stability, the guessed-ID null, the
 * malformed/raw/mistyped-ID VAL-001 legs, the anonymous rejection (the
 * /graphql chain's 401 problem+json contract), the legacy variant's
 * honest null name, the empty page, and the classified errors for the
 * declared-but-unexecuted arguments (ids/filter/where/search/channel/
 * before/last/sku/externalReference/keyless — never silent
 * no-ops).</p>
 *
 * <p>The real migrated schema, not Hibernate's ddl-auto approximation
 * (the ProductGraphQlIntegrationTest precedent): the scrolls and the
 * seeded rows run against the Flyway V1..V247 schema — the honest
 * standing shape (the seed migration's own rows are part of the
 * population the assertions reason about relatively, never by absolute
 * count).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductVariantGraphQlIntegrationTest {

    private static final String CONSUMER_SUBJECT = "p2-vars-consumer-subject";
    private static final String ADMIN_SUBJECT = "p2-vars-admin-subject";

    /** The base64 prefix every typed ProductVariant global ID starts with on the wire. */
    private static final String VARIANT_ID_PREFIX = java.util.Base64.getEncoder()
            .encodeToString("ProductVariant:".getBytes(java.nio.charset.StandardCharsets.UTF_8));

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
    private ProductVariantRepository variantRepository;

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
    private final List<UUID> seededVariantIds = new ArrayList<>();

    /** The seeded rows the population assertions reason about by their unique SKUs. */
    private String publicSkuOne;
    private String publicSkuTwo;
    private String variantUnderDraftParentSku;
    private String suspendedVariantSku;
    private UUID variantUnderDraftParentId;
    private UUID suspendedVariantId;
    private UUID namelessVariantId;

    @BeforeEach
    void seedReferenceRows() {
        providerId = UUID.randomUUID();
        userRepository.save(new User(providerId, providerId.toString(), null,
                "b5a test provider", com.marketplace.identity.UserRole.PROVIDER));

        storeCategoryCode = "b5a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), storeCategoryCode, "b5a test", "اختبار المتغيرات");

        // The typed DRAFT parent product needs a REAL goods schema (the
        // validated FK); the ACTIVE parents keep the legacy shape.
        ProductType type = ProductType.create("b5a-type-" + UUID.randomUUID().toString().substring(0, 8),
                "b5a Variant Schema", null, null);
        productTypeRepository.save(type);
        seededTypeId = type.getId();

        String run = UUID.randomUUID().toString().substring(0, 8);
        publicSkuOne = "b5a-" + run + "-one";
        publicSkuTwo = "b5a-" + run + "-two";
        variantUnderDraftParentSku = "b5a-" + run + "-draft-parent";
        suspendedVariantSku = "b5a-" + run + "-suspended";

        persistVariantUnderActiveParent(publicSkuOne, "b5a Large");
        namelessVariantId = persistVariantUnderActiveParent(publicSkuTwo, null);
        variantUnderDraftParentId = persistVariantUnderParent(persistDraftParent(), variantUnderDraftParentSku);
        suspendedVariantId = persistSuspendedVariant();
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededVariantIds.isEmpty()) {
            variantRepository.deleteAllById(new ArrayList<>(seededVariantIds));
            seededVariantIds.clear();
        }
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

    private Product persistActiveParent() {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, "b5a Variant Parent " + UUID.randomUUID().toString().substring(0, 6),
                "b5a fixture", 1000L, "SAR", providerId));
        seededProductIds.add(product.getId());
        return product;
    }

    private Product persistDraftParent() {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, "b5a Draft Variant Parent", "b5a fixture", 1000L, "SAR",
                providerId, seededTypeId));
        seededProductIds.add(product.getId());
        return product;
    }

    private UUID persistVariantUnderActiveParent(String sku, String name) {
        Product parent = persistActiveParent();
        ProductVariant variant = variantRepository.save(
                ProductVariant.register(parent, sku, name, 900L));
        seededVariantIds.add(variant.getId());
        return variant.getId();
    }

    private UUID persistVariantUnderParent(Product parent, String sku) {
        ProductVariant variant = variantRepository.save(
                ProductVariant.register(parent, sku, "b5a fixture variant", 900L));
        seededVariantIds.add(variant.getId());
        return variant.getId();
    }

    private UUID persistSuspendedVariant() {
        Product parent = persistActiveParent();
        ProductVariant variant = ProductVariant.register(parent, suspendedVariantSku, "b5a fixture variant", 900L);
        variant.suspend();
        variantRepository.save(variant);
        seededVariantIds.add(variant.getId());
        return variant.getId();
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

    /** A well-formed typed global ID for the ProductVariant type over any native UUID. */
    private static String variantGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductVariant:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // productVariants — the permission-aware two-row-gated population
    // ------------------------------------------------------------------

    @Test
    void consumerSeesOnlyTheTwoRowGatedPopulationWithItsOwnCount() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 50) {
                            edges { node { sku status name product { status } } }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges[*].node.status").entityList(String.class)
                .containsExactly("ACTIVE")
                .path("productVariants.edges[*].node.product.status").entityList(String.class)
                .containsExactly("ACTIVE")
                .path("productVariants.edges[*].node.sku").entityList(String.class)
                .satisfies(skus -> {
                    // BOTH data-scope negatives: the ACTIVE variant under the
                    // DRAFT parent AND the SUSPENDED variant are absent.
                    assertThat(skus).contains(publicSkuOne, publicSkuTwo);
                    assertThat(skus).doesNotContain(variantUnderDraftParentSku, suspendedVariantSku);
                })
                .path("productVariants.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(2));
    }

    @Test
    void adminSeesTheIncludeUnpublishedPopulationWithTheWholeCount() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query { productVariants(first: 100) {
                            edges { node { sku status product { status } } }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges[*].node.sku").entityList(String.class)
                .satisfies(skus -> {
                    // The include-unpublished mapping: the draft-parent's
                    // variant AND the suspended variant are both present.
                    assertThat(skus).contains(publicSkuOne, variantUnderDraftParentSku, suspendedVariantSku);
                })
                .path("productVariants.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(4));
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameIdScrollWithoutOverlap() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String firstPageEndCursor = tester.document("""
                        query { productVariants(first: 1) {
                            edges { node { sku } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges").entityList(Object.class).hasSize(1)
                .path("productVariants.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("productVariants.pageInfo.endCursor").entity(String.class).get();

        String firstPageSku = tester.document("""
                        query { productVariants(first: 1) { edges { node { sku } } } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges[0].node.sku").entity(String.class).get();

        tester.document("""
                        query($after: String) { productVariants(first: 50, after: $after) {
                            edges { node { sku } }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .errors().verify()
                .path("productVariants.edges[*].node.sku").entityList(String.class)
                .satisfies(skus -> assertThat(skus).doesNotContain(firstPageSku));
    }

    @Test
    void lastModifiedSortScrollsAndPagesToo() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String firstPageEndCursor = tester.document("""
                        query { productVariants(first: 1, sortBy: { direction: DESC, field: LAST_MODIFIED_AT }) {
                            edges { node { sku } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges").entityList(Object.class).hasSize(1)
                .path("productVariants.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("productVariants.pageInfo.endCursor").entity(String.class).get();

        tester.document("""
                        query($after: String) { productVariants(first: 50, after: $after,
                                sortBy: { direction: DESC, field: LAST_MODIFIED_AT }) {
                            edges { node { sku } }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .errors().verify()
                .path("productVariants.edges[*].node.sku").entityList(String.class)
                .hasSizeGreaterThan(0);
    }

    @Test
    void namelessLegacyVariantAnswersTheHonestNullName() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        // The legacy free-form variant's own row carries no display name —
        // the bounded projection answers the honest null, never a
        // fabricated placeholder (the registered nullable-name divergence).
        tester.document("""
                        "query($id: ID!) { productVariant(id: $id) { sku name } }"
                        """)
                .variable("id", variantGlobalId(namelessVariantId))
                .execute()
                .errors().verify()
                .path("productVariant.sku").entity(String.class).isEqualTo(publicSkuTwo)
                .path("productVariant.name").valueIsNull();
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 0) {
                            edges { node { sku } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges").entityList(Object.class).hasSize(0)
                .path("productVariants.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("productVariants.totalCount").valueIsNull();
    }

    // ------------------------------------------------------------------
    // productVariant — the permission-aware bounded lookup
    // ------------------------------------------------------------------

    @Test
    void consumerLookupOfTheVariantUnderTheUnpublishedParentAnswersTheSafeNull() {
        // The two-row data-scope negative: the variant row EXISTS and is
        // itself ACTIVE, but its parent product is DRAFT — the public
        // caller's lookup answers the safe null, the ADMIN reads it.
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        query($id: ID!) { productVariant(id: $id) { id } }
                        """)
                .variable("id", variantGlobalId(variantUnderDraftParentId))
                .execute()
                .errors().verify()
                .path("productVariant").valueIsNull();

        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        query($id: ID!) { productVariant(id: $id) { id sku product { status } } }
                        """)
                .variable("id", variantGlobalId(variantUnderDraftParentId))
                .execute()
                .errors().verify()
                .path("productVariant.sku").entity(String.class).isEqualTo(variantUnderDraftParentSku)
                .path("productVariant.product.status").entity(String.class).isEqualTo("DRAFT");
    }

    @Test
    void consumerLookupOfTheSuspendedVariantAnswersTheSafeNull() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        query($id: ID!) { productVariant(id: $id) { id } }
                        """)
                .variable("id", variantGlobalId(suspendedVariantId))
                .execute()
                .errors().verify()
                .path("productVariant").valueIsNull();

        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        query($id: ID!) { productVariant(id: $id) { status } }
                        """)
                .variable("id", variantGlobalId(suspendedVariantId))
                .execute()
                .errors().verify()
                .path("productVariant.status").entity(String.class).isEqualTo("SUSPENDED");
    }

    @Test
    void variantRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String emittedId = tester.document("""
                        query { productVariants(first: 1) { edges { node { id sku product { id status } } } } }
                        """)
                .execute()
                .errors().verify()
                .path("productVariants.edges[0].node.id").entity(String.class).get();

        assertThat(emittedId).startsWith(VARIANT_ID_PREFIX);
        tester.document("""
                        query($id: ID!) { productVariant(id: $id) { id sku } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("productVariant.id").entity(String.class).isEqualTo(emittedId)
                .path("productVariant.sku").entity(String.class).satisfies(
                        sku -> assertThat(String.valueOf(sku)).startsWith("b5a-"));
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($id: ID!) { productVariant(id: $id) { id } }
                        """)
                .variable("id", variantGlobalId(UUID.randomUUID()))
                .execute()
                .errors().verify()
                .path("productVariant").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the typed global-ID codec's classified validation errors
    // ------------------------------------------------------------------

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { productVariant(id: $id) { id } }
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
                        query($id: ID!) { productVariant(id: $id) { id } }
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
                        query($id: ID!) { productVariant(id: $id) { id } }
                        """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the anonymous boundary — the /graphql chain's 401 problem+json
    // ------------------------------------------------------------------

    @Test
    void anonymousProductVariantQueryIsRejectedAs401ProblemDetail() {
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ productVariants(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType("application/problem+json")
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"marketplace\"")
                .expectBody()
                .jsonPath("$.type").isEqualTo("https://marketplace.com/errors/unauthorized")
                .jsonPath("$.title").isEqualTo("Unauthorized")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.instance").isEqualTo("/graphql")
                .jsonPath("$.errorCode").isEqualTo("AUTHN-001");
    }

    // ------------------------------------------------------------------
    // the classified not-implemented boundaries — never silent no-ops
    // ------------------------------------------------------------------

    @Test
    void idsFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 2, ids: ["b5a-id"]) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("ids filtering is not implemented")));
    }

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 2, filter: { search: "x" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("filter is not implemented")));
    }

    @Test
    void whereFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 2, where: { ids: ["x"] }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("where filter is not implemented")));
    }

    @Test
    void searchAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 2, search: "red") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("search is not implemented")));
    }

    @Test
    void channelAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(first: 2, channel: "channel-1") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("channel filtering is not implemented")));
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariants(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("forward-only pagination")));
    }

    @Test
    void skuLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($sku: String) { productVariant(sku: $sku) { id } }
                        """)
                .variable("sku", "b5a-sku")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("sku lookup is not implemented")));
    }

    @Test
    void externalReferenceLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($externalReference: String) { productVariant(externalReference: $externalReference) { id } }
                        """)
                .variable("externalReference", "erp-variant-1")
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("externalReference lookup is not implemented")));
    }

    @Test
    void keylessLookupAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { productVariant { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy((ResponseError error) -> assertThat(error.getMessage())
                                .contains("productVariant requires its id")));
    }
}

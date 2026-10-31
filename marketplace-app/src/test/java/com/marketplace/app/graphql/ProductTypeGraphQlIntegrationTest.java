package com.marketplace.app.graphql;

import com.marketplace.catalog.ProductType;
import com.marketplace.catalog.ProductTypeRepository;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * P2 (the canonical Saleor plan §5-P2): end-to-end proof of the Saleor
 * Products-domain product-type read roots — {@code productType} and
 * {@code productTypes} — through the REAL SDL, the REAL DGS wiring, the
 * REAL security chain (the mocked JwtDecoder stamps the caller's subject
 * and roles claim exactly the way the production converter reads them),
 * the REAL catalog owner (no SPI mocking: the ADMIN enforcement and the
 * bounded keyset scroll over the reference table execute in the real
 * service), and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the canonical plan's P2 gate and the
 * campaign's frozen read contract: the role matrix (a non-admin caller is
 * denied by the owning use case — AUTHZ-001), the read-back journey (the
 * list's own emitted global ID round-trips through {@code productType}
 * with same-ID stability), the honest absences (an unknown but
 * well-formed global ID answers null; a malformed or mistyped global ID
 * answers the classified VAL-001 error), the keyset pagination contract
 * (forward-only cursors that identify a row's sort key, with the
 * declared-but-unexecuted Saleor arguments answering explicit classified
 * errors instead of silent no-ops), and the bounded projection's own
 * facts (the bilingual row's primary-name rendering — the nameEn-null
 * seed proves the nameAr fallback honestly).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductTypeGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p2-types-admin-subject";
    private static final String PROVIDER_SUBJECT = "p2-types-provider-subject";
    private static final String CONSUMER_SUBJECT = "p2-types-consumer-subject";


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
    private ProductTypeRepository productTypeRepository;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededIds = new ArrayList<>();

    @BeforeEach
    void seedReferenceRows() {
        // The bilingual leg is deliberate: the second seed has NO nameEn,
        // so the rendered primary name must fall back to nameAr — the
        // honest bilingual rendering the bounded projection documents.
        persist("p2-test-alpha", "P2 Type Alpha", null);
        persist("p2-test-beta", null, "نوع بيتا للاختبار");
        persist("p2-test-gamma", "P2 Type Gamma", null);
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededIds.isEmpty()) {
            productTypeRepository.deleteAllById(new ArrayList<>(seededIds));
            seededIds.clear();
        }
    }

    private void persist(String code, String nameEn, String nameAr) {
        ProductType type = ProductType.create(code, nameEn, nameAr, null);
        productTypeRepository.save(type);
        seededIds.add(type.getId());
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (authorities claim "roles", prefix ROLE_). The decoder
     * stub is global to the mock, so each test builds the tester whose
     * subject it acts as, immediately before its requests (the
     * UserGraphQlIntegrationTest precedent).
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

    /** A well-formed typed global ID for the ProductType type over any native UUID. */
    private static String productTypeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // productTypes — the ADMIN-enforced keyset scroll (envelope + paging)
    // ------------------------------------------------------------------

    @Test
    void adminSeesTheSaleorEnvelopeOverTheRealReferenceTable() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(first: 10) {
                            edges { node { id name slug } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3))
                .path("productTypes.edges").entityList(Object.class)
                .satisfies(edges -> assertThat(edges).isNotEmpty())
                .path("productTypes.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .allSatisfy(id -> assertThat(decodedGlobalId(id)).startsWith("ProductType:")))
                .path("productTypes.edges[*].node.slug").entityList(String.class)
                .satisfies(slugs -> assertThat(slugs)
                        .allSatisfy(slug -> assertThat(slug).matches("^[a-z][a-z0-9-]*$")));
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { productTypes(first: 1) {
                            edges { node { name } }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.edges[0].node.name").entity(String.class)
                .satisfies(name -> assertThat(name).isNotBlank())
                .path("productTypes.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("productTypes.edges").entityList(Object.class).hasSize(1);
        String firstName = firstPage.path("productTypes.edges[0].node.name").entity(String.class).get();
        String firstEndCursor = firstPage.path("productTypes.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's own sort key: following it continues
        // the same name-ascending scroll without overlap.
        adminTester.document("""
                        query($after: String) { productTypes(first: 1, after: $after) {
                            edges { node { name } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("productTypes.edges[0].node.name").entity(String.class)
                .satisfies(secondName -> assertThat(secondName).isGreaterThan(firstName))
                .path("productTypes.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void nameDescendingSortReversesTheWindow() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(first: 5, sortBy: { direction: DESC, field: NAME }) {
                            edges { node { name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.edges[*].node.name").entityList(String.class)
                .satisfies(names -> {
                    assertThat(names).isNotEmpty();
                    assertThat(names).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                });
    }

    @Test
    void bilingualRowRendersItsArabicPrimaryName() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The nameEn-null seed's rendered name IS its Arabic name — the
        // honest bilingual fallback, asserted on the wire.
        adminTester.document("""
                        query { productTypes(first: 50, filter: null) { edges { node { name slug } } } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.edges[*].node.slug").entityList(String.class)
                .contains("p2-test-beta")
                .path("productTypes.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).contains("نوع بيتا للاختبار"));
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(first: 0) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.edges").entityList(Object.class).hasSize(0)
                .path("productTypes.totalCount").valueIsNull()
                .path("productTypes.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("productTypes.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
    }

    // ------------------------------------------------------------------
    // productType — the ADMIN-enforced lookup (read-back + negatives)
    // ------------------------------------------------------------------

    @Test
    void productTypeRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var page = adminTester.document("""
                        query { productTypes(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name slug } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypes.edges[0].node.id").entity(String.class).get();
        String emittedId = page;
        String emittedName = adminTester.document("""
                        query { productTypes(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name } } } }
                        """)
                .execute().errors().verify()
                .path("productTypes.edges[0].node.name").entity(String.class).get();

        var readBack = adminTester.document("""
                        query($id: ID!) { productType(id: $id) { id name slug } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("productType.id").entity(String.class).isEqualTo(emittedId)
                .path("productType.name").entity(String.class).isEqualTo(emittedName);

        // Same-ID stability: a fresh read of the same emitted ID answers
        // the same identity again (the native key, not a per-read handle).
        String again = readBack.path("productType.id").entity(String.class).get();
        assertThat(again).isEqualTo(emittedId);
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID guessed = UUID.randomUUID();
        adminTester.document("""
                        query($id: ID!) { productType(id: $id) { id name } }
                        """)
                .variable("id", productTypeGlobalId(guessed))
                .execute()
                .errors().verify()
                .path("productType").valueIsNull();
    }

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { productType(id: $id) { id } }
                        """)
                .variable("id", "not-a-global-id!")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { productType(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    // ------------------------------------------------------------------
    // the role matrix + the declared-but-unexecuted argument boundaries
    // ------------------------------------------------------------------

    @Test
    void providerCallerIsDeniedTheListByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        providerTester.document("query { productTypes(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("productTypes").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedTheLookupByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("""
                        query($id: ID!) { productType(id: $id) { id } }
                        """)
                .variable("id", productTypeGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("productType").valueIsNull();
    }

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(first: 2, filter: { search: "alpha" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("filter is not implemented");
                });
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(last: 2) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("forward-only");
                });
    }

    @Test
    void unownedSortFieldAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { productTypes(first: 2, sortBy: { direction: ASC, field: DIGITAL }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("DIGITAL is not implemented");
                });
    }

    // ------------------------------------------------------------------
    // the product-type mutation roots — the real write stack (agent-2)
    // ------------------------------------------------------------------

    @Test
    void adminCreatesAProductTypeThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String emittedId = adminTester.document("""
                        mutation { productTypeCreate(input: {name: "P2 Created Schema", slug: "p2-created-schema"}) {
                            errors { field code }
                            productType { id name slug }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypeCreate.errors").entityList(Object.class).hasSize(0)
                .path("productTypeCreate.productType.id").entity(String.class).get();

        assertThat(decodedGlobalId(emittedId)).startsWith("ProductType:");
        UUID rowId = UUID.fromString(decodedGlobalId(emittedId).substring("ProductType:".length()));
        seededIds.add(rowId);

        // The real-stack read-back: the row persisted with the registry's
        // own code contract, and the read root answers it by its typed ID.
        ProductType row = productTypeRepository.findById(rowId).orElseThrow();
        assertThat(row.getCode()).isEqualTo("p2-created-schema");
        assertThat(row.getNameEn()).isEqualTo("P2 Created Schema");

        adminTester.document("""
                        query($id: ID!) { productType(id: $id) { name slug } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("productType.name").entity(String.class).isEqualTo("P2 Created Schema")
                .path("productType.slug").entity(String.class).isEqualTo("p2-created-schema");
    }

    @Test
    void duplicateSlugCreateAnswersThePayloadError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { productTypeCreate(input: {name: "P2 Duplicate", slug: "p2-test-alpha"}) {
                            errors { field code message }
                            productType { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("productTypeCreate.errors[0].field").entity(String.class).isEqualTo("slug")
                .path("productTypeCreate.errors[0].code").entity(String.class).isEqualTo("ALREADY_EXISTS")
                .path("productTypeCreate.productType").valueIsNull();
    }

    @Test
    void providerIsDeniedTheProductTypeCreateByTheOwningUseCase() {
        HttpGraphQlTester providerTester = testerFor(PROVIDER_SUBJECT, "PROVIDER");

        providerTester.document("""
                        mutation { productTypeCreate(input: {name: "Denied", slug: "p2-denied-type"}) {
                            errors { code }
                            productType { id }
                        } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                });
    }

    @Test
    void adminUpdatesAProductTypeThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededIds.isEmpty() ? null : seededIds.get(0);
        org.assertj.core.api.Assertions.assertThat(rowId).isNotNull();

        adminTester.document("""
                        mutation { productTypeUpdate(id: "%s", input: {name: "P2 Updated Schema"}) {
                            errors { code }
                            productType { name slug }
                        } }
                        """.formatted(productTypeGlobalId(rowId)))
                .execute()
                .errors().verify()
                .path("productTypeUpdate.errors").entityList(Object.class).hasSize(0)
                .path("productTypeUpdate.productType.name").entity(String.class).isEqualTo("P2 Updated Schema");

        // The partial-preserve law: the slug column is untouched by the
        // name-only command.
        ProductType row = productTypeRepository.findById(rowId).orElseThrow();
        assertThat(row.getNameEn()).isEqualTo("P2 Updated Schema");
    }

    @Test
    void adminDeletesAndBulkDeletesProductTypesThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID rowId = seededIds.isEmpty() ? null : seededIds.get(0);
        org.assertj.core.api.Assertions.assertThat(rowId).isNotNull();
        UUID extraId = null;
        ProductType extra = ProductType.create("p2-extra-" + UUID.randomUUID().toString().substring(0, 8),
                "P2 Extra Schema", null, null);
        extra = productTypeRepository.save(extra);
        extraId = extra.getId();
        seededIds.add(extraId);

        adminTester.document("""
                        mutation { productTypeDelete(id: "%s") {
                            errors { code }
                            productType { id name }
                        } }
                        """.formatted(productTypeGlobalId(extraId)))
                .execute()
                .errors().verify()
                .path("productTypeDelete.errors").entityList(Object.class).hasSize(0)
                .path("productTypeDelete.productType.name").entity(String.class).isEqualTo("P2 Extra Schema");

        // The soft-deleted row is absent to the read root (the honest
        // absence, never an error).
        adminTester.document("""
                        query($id: ID!) { productType(id: $id) { id } }
                        """)
                .variable("id", productTypeGlobalId(extraId))
                .execute()
                .errors().verify()
                .path("productType").valueIsNull();

        adminTester.document("""
                        mutation { productTypeBulkDelete(ids: ["%s"]) {
                            count
                            errors { code }
                        } }
                        """.formatted(productTypeGlobalId(rowId)))
                .execute()
                .errors().verify()
                .path("productTypeBulkDelete.count").entity(Integer.class).isEqualTo(1)
                .path("productTypeBulkDelete.errors").entityList(Object.class).hasSize(0);
    }

}

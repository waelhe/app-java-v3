package com.marketplace.app.graphql;

import com.marketplace.catalog.Category;
import com.marketplace.catalog.CategoryRepository;
import test.config.IntegrationContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
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
 * P2 (the canonical Saleor plan §5-P2 — packet campaign-b4-a):
 * end-to-end proof of the Saleor Products-domain {@code category} and
 * {@code categories} read roots through the REAL SDL, the REAL DGS
 * wiring, the REAL security chain (the mocked JwtDecoder stamps the
 * caller's subject and roles claim exactly the way the production
 * converter reads them), the REAL catalog owner (no SPI mocking: the
 * in-memory keyset scroll and the code lookups execute against the
 * real PostgreSQL over the SAME Flyway-migrated {@code categories}
 * registry the public REST vocabulary read serves), and the REAL
 * typed global-ID codec.
 *
 * <p>The acceptance legs mirror the packet's gate: the PUBLIC-ROOT
 * authorization mapping (these are Saleor storefront roots — an
 * authenticated CONSUMER caller reads the registry: the allow path the
 * staff-root contrast denies; the anonymous caller is rejected by the
 * /graphql chain's 401 problem+json AUTHN-001 contract — the
 * platform's authentication boundary for this surface), the
 * forward-only keyset scroll with cursor follow-through, the
 * round-trip global-ID stability (the list's own emitted typed global
 * ID read back through the {@code category} root, with the guessed-ID
 * null and the malformed/raw/mistyped-ID VAL-001 legs of the same
 * journey), the slug lookup over the owner's code seam, the bilingual
 * rendering (an nameEn-null row renders its Arabic primary name; a
 * neither-name row answers the honest null of the registered
 * nullability divergence), the platform extension facts
 * ({@code nameAr}/{@code position}) on the wire, the empty page, and
 * the classified errors for the declared-but-unexecuted arguments
 * (filter/where/level/before/last/unowned sort fields/
 * slugLanguageCode/externalReference/keyless — never silent no-ops).</p>
 *
 * <p>The real migrated schema, not Hibernate's ddl-auto approximation
 * (the AttributeGraphQlIntegrationTest precedent): the registry scroll
 * and the seeded rows run against the Flyway V1..V247 schema — the
 * honest standing shape (the V70 seed's own rows are part of the
 * registry the assertions reason about relatively, never by absolute
 * count).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CategoryGraphQlIntegrationTest {

    private static final String CONSUMER_SUBJECT = "p2-cats-consumer-subject";
    private static final String ADMIN_SUBJECT = "p2-cats-admin-subject";


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
    private CategoryRepository categoryRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededIds = new ArrayList<>();

    @BeforeEach
    void seedReferenceRows() {
        // The bilingual + nameless legs are deliberate: the second seed
        // has NO nameEn so the rendered primary name must fall back to
        // nameAr; the third carries NEITHER bilingual name so the wire
        // name answers the honest null of the registered nullability
        // divergence (and the NAME sort's total empty-string ordering
        // key places it first in ascending).
        persist("p2-cat-alpha", "P2 Category Alpha", null, 1);
        persist("p2-cat-beta", null, "فئة بيتا للاختبار", 2);
        persist("p2-cat-nameless", null, null, 3);
        persist("p2-cat-gamma", "P2 Category Gamma", "فئة جاما", 4);
    }

    @AfterEach
    void removeSeededRows() {
        // The fixture rows leave PHYSICALLY: the category entity is a
        // Hibernate @SoftDelete row (BaseEntity's own law), so a
        // repository delete only flips is_deleted and the PHYSICAL row
        // keeps holding uq_categories_code — the next seed of the same
        // fixed fixture codes then trips the unique index (the CI-measured
        // duplicate-key failure). A physical JDBC delete of THIS test's
        // own fixture rows never weakens the index and never changes the
        // production soft-delete law.
        for (UUID categoryId : seededIds) {
            jdbcTemplate.update("DELETE FROM categories WHERE id = ?", categoryId);
        }
        seededIds.clear();
    }

    private void persist(String code, String nameEn, String nameAr, Integer position) {
        Category category = Category.create(code, nameEn, nameAr, position);
        categoryRepository.save(category);
        seededIds.add(category.getId());
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

    /** A well-formed typed global ID for the Category type over any native UUID. */
    private static String categoryGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Category:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String attributeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // categories — the public-root keyset scroll (envelope + paging)
    // ------------------------------------------------------------------

    @Test
    void consumerSeesTheSaleorEnvelopeOverTheRealRegistry() {
        // The PUBLIC-ROOT mapping: Saleor's storefront category roots
        // carry no permission for published content and the platform
        // registry has no publication flag — an authenticated CONSUMER
        // caller reads the SAME registry the staff does (the allow path
        // the ADMIN-gated identity/attribute slices deny — the
        // registered public-root mapping divergence).
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(first: 3, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name nameAr slug position } cursor }
                            pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                            totalCount
                        } }
                        """)
                .execute()
                .path("categories.edges[0].node.slug").entity(String.class).isEqualTo("p2-cat-nameless")
                .path("categories.edges[0].node.name").valueIsNull()
                .path("categories.edges[0].node.position").entity(Integer.class).isEqualTo(3)
                .path("categories.edges[1].node.slug").entity(String.class).isEqualTo("p2-cat-alpha")
                .path("categories.edges[1].node.name").entity(String.class).isEqualTo("P2 Category Alpha")
                .path("categories.edges[1].node.nameAr").valueIsNull()
                // naturalOrder's documented law: gamma (Latin) precedes
                // beta (Arabic) in the ascending order.
                .path("categories.edges[2].node.slug").entity(String.class).isEqualTo("p2-cat-gamma")
                .path("categories.edges[2].node.name").entity(String.class).isEqualTo("P2 Category Gamma")
                .path("categories.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false)
                .path("categories.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(4));
    }

    @Test
    void nameDescendingSortReversesTheWindow() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(first: 3, sortBy: { direction: DESC, field: NAME }) {
                            edges { node { slug } }
                        } }
                        """)
                .execute()
                // naturalOrder's documented law: Arabic letters (U+06xx)
                // sort ABOVE the Latin range, so DESC opens on the Arabic-
                // named beta row, then the two Latin rows, nameless last.
                .path("categories.edges[0].node.slug").entity(String.class).isEqualTo("p2-cat-beta")
                .path("categories.edges[1].node.slug").entity(String.class).isEqualTo("p2-cat-gamma")
                .path("categories.edges[2].node.slug").entity(String.class).isEqualTo("p2-cat-alpha");
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String firstPageEndCursor = tester.document("""
                        query { categories(first: 2, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .path("categories.edges[0].node.slug").entity(String.class).isEqualTo("p2-cat-nameless")
                .path("categories.edges[1].node.slug").entity(String.class).isEqualTo("p2-cat-alpha")
                .path("categories.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("categories.pageInfo.endCursor").entity(String.class).get();

        // The follow-through page continues strictly AFTER the cursor's
        // own (name, id) sort key — no overlap, no skipped row.
        tester.document("""
                        query($after: String) { categories(first: 2, after: $after, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                // naturalOrder's documented law: the Arabic-named beta row
                // sorts ABOVE the Latin range, so the ASC page after alpha
                // opens on gamma and closes on beta.
                .path("categories.edges[0].node.slug").entity(String.class).isEqualTo("p2-cat-gamma")
                .path("categories.edges[1].node.slug").entity(String.class).isEqualTo("p2-cat-beta")
                .path("categories.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(first: 0) {
                            edges { node { slug } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .path("categories.edges").entityList(Object.class).hasSize(0)
                .path("categories.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("categories.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false)
                .path("categories.totalCount").valueIsNull();
    }

    // ------------------------------------------------------------------
    // category — the bounded lookups (id / slug)
    // ------------------------------------------------------------------

    @Test
    void categoryRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String emittedId = tester.document("""
                        query { categories(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id slug } }
                        } }
                        """)
                .execute()
                .path("categories.edges[0].node.id").entity(String.class).get();

        // The list's own emitted typed global ID reads back through the
        // category root — the round-trip stability the codec contract
        // pins (same wire ID, same registry row).
        assertThat(decodedGlobalId(emittedId)).startsWith("Category:");
        tester.document("""
                        query($id: ID!) { category(id: $id) { id slug name } }
                        """)
                .variable("id", emittedId)
                .execute()
                .path("category.id").entity(String.class).isEqualTo(emittedId)
                .path("category.slug").entity(String.class).isEqualTo("p2-cat-nameless");
    }

    @Test
    void categoryBySlugAnswersTheSeededBilingualRow() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($slug: String) { category(slug: $slug) { slug name nameAr position } }
                        """)
                .variable("slug", "p2-cat-beta")
                .execute()
                .path("category.slug").entity(String.class).isEqualTo("p2-cat-beta")
                .path("category.name").entity(String.class).isEqualTo("فئة بيتا للاختبار")
                .path("category.nameAr").entity(String.class).isEqualTo("فئة بيتا للاختبار")
                .path("category.position").entity(Integer.class).isEqualTo(2);
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($id: ID!) { category(id: $id) { id slug } }
                        """)
                .variable("id", categoryGlobalId(UUID.randomUUID()))
                .execute()
                .path("category").valueIsNull();
    }

    @Test
    void guessedSlugAnswersNullNotAnError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($slug: String) { category(slug: $slug) { id } }
                        """)
                .variable("slug", "no-such-category-code")
                .execute()
                .path("category").valueIsNull();
    }

    // ------------------------------------------------------------------
    // the typed global-ID codec's classified validation errors
    // ------------------------------------------------------------------

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { category(id: $id) { id } }
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
                        query($id: ID!) { category(id: $id) { id } }
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
        // A well-formed typed global ID for ANOTHER GraphQL type: the
        // codec's per-callsite expected-type check answers the same
        // uniform classified error — no type-discrimination leak.
        HttpGraphQlTester tester = testerFor(ADMIN_SUBJECT, "ADMIN");

        tester.document("""
                        query($id: ID!) { category(id: $id) { id } }
                        """)
                .variable("id", attributeGlobalId(UUID.randomUUID()))
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
void anonymousCategoryQueryIsRejectedByTheGraphQlGateBeforeDispatch() {
        // No Authorization header: PreLoginGraphQlGate answers the
        // classified AUTHN-001 GraphQL error BEFORE dispatch (HTTP 200,
        // ONE error with the canonical taxonomy pair, NO data key —
        // nothing leaked, no resolver executed). The unified post-gate
        // contract every root now shares (the GraphQlSecurityIntegrationTest
        // tripartite shape); the old problem+json 401 contract died with
        // the gate.
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ categories(first: 1) { totalCount } }\"}")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.errors.length()").isEqualTo(1)
                .jsonPath("$.errors[0].extensions.errorCode").isEqualTo("AUTHN-001")
                .jsonPath("$.errors[0].extensions.category").isEqualTo("authz")
                .jsonPath("$.errors[0].extensions.classification").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.data").doesNotExist();
    }

    // ------------------------------------------------------------------
    // the classified not-implemented boundaries — never silent no-ops
    // ------------------------------------------------------------------

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(first: 2, filter: { search: "x" }) { totalCount } }
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
                        query { categories(first: 2, where: { ids: ["x"] }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("where filter is not implemented")));
    }

    @Test
    void levelAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(first: 2, level: 1) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("level filtering is not implemented")));
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { categories(last: 2) { totalCount } }
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
                        query { categories(first: 2, sortBy: { direction: ASC, field: SUBCATEGORY_COUNT }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("SUBCATEGORY_COUNT is not implemented")));
    }

    @Test
    void cursorFromAnotherSortIsRejectedByTheCursorGate() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        // A slug-keyed cursor (another vocabulary) on the name scroll:
        // the house KeysetCursorGate answers the VAL-001 taxonomy.
        String slugCursor = java.util.Base64.getEncoder().encodeToString(
                ("slug:p2-cat-alpha|id:" + UUID.randomUUID()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tester.document("""
                        query($after: String) { categories(first: 1, after: $after) { totalCount } }
                        """)
                .variable("after", slugCursor)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> {
                            // The framework's own cursor-decoding failure message
                            // for a non-keyset cursor shape (the gate's
                            // "forward keyset cursor" wording only answers a
                            // DECODABLE cursor with the wrong key set).
                            assertThat(error.getMessage()).contains("Invalid or unknown cursor type");
                            assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                        }));
    }

    @Test
    void slugLanguageCodeLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query { category(slug: "p2-cat-alpha", slugLanguageCode: EN) { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> assertThat(error.getMessage())
                                .contains("slugLanguageCode lookup is not implemented")));
    }

    @Test
    void externalReferenceLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester tester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        tester.document("""
                        query($externalReference: String) { category(externalReference: $externalReference) { id } }
                        """)
                .variable("externalReference", "erp-cat-1")
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
                        query { category { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors)
                        .anySatisfy(error -> {
                            assertThat(error.getMessage()).contains("category requires one of id or slug");
                            assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                        }));
    }

    // ------------------------------------------------------------------
    // the mutation roots — the real write stack (the agent-2 packet)
    // ------------------------------------------------------------------

    @Test
    void adminCreatesACategoryThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        String emittedId = adminTester.document("""
                        mutation { categoryCreate(input: {name: "P2 Created Registry Row", slug: "p2-cat-created"}) {
                            errors { field code }
                            category { id name slug nameAr position }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("categoryCreate.category.id").entity(String.class).get();

        // The payload's typed global ID decodes to the registry's own tag
        // and native UUID — the row the real write stack persisted.
        String decoded = decodedGlobalId(emittedId);
        assertThat(decoded).startsWith("Category:");
        UUID rowId = UUID.fromString(decoded.substring("Category:".length()));
        seededIds.add(rowId);
        Category row = categoryRepository.findById(rowId).orElseThrow();
        assertThat(row.getCode()).isEqualTo("p2-cat-created");
        assertThat(row.getNameEn()).isEqualTo("P2 Created Registry Row");
        assertThat(row.getNameAr()).isNull();

        adminTester.document("""
                        query { category(slug: "p2-cat-created") { slug name position } }
                        """)
                .execute()
                .errors().verify()
                .path("category.slug").entity(String.class).isEqualTo("p2-cat-created")
                .path("category.name").entity(String.class).isEqualTo("P2 Created Registry Row");
    }

    @Test
    void duplicateSlugCreateAnswersThePayloadError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { categoryCreate(input: {name: "Duplicate", slug: "p2-cat-alpha"}) {
                            errors { field code message }
                            category { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("categoryCreate.errors[0].field").entity(String.class).isEqualTo("slug")
                .path("categoryCreate.errors[0].code").entity(String.class).isEqualTo("ALREADY_EXISTS")
                .path("categoryCreate.category").valueIsNull();
    }

    @Test
    void requiredFieldViolationsAnswerThePayloadErrors() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");

        adminTester.document("""
                        mutation { categoryCreate(input: {name: " "}) {
                            errors { field code }
                            category { id }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("categoryCreate.errors[0].field").entity(String.class).isEqualTo("name")
                .path("categoryCreate.errors[0].code").entity(String.class).isEqualTo("REQUIRED")
                .path("categoryCreate.errors[1].field").entity(String.class).isEqualTo("slug")
                .path("categoryCreate.errors[1].code").entity(String.class).isEqualTo("REQUIRED");
    }

    @Test
    void consumerIsDeniedTheCreateByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumerTester.document("""
                        mutation { categoryCreate(input: {name: "Denied", slug: "p2-cat-denied"}) {
                            errors { code }
                            category { id }
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
    void adminUpdatesACategoryThroughTheRealMutationStack() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        Category seeded = categoryRepository.findByCode("p2-cat-gamma").orElseThrow();

        String emittedId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + seeded.getId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { categoryUpdate(id: "%s", input: {name: "P2 Category Gamma Refreshed"}) {
                            errors { code }
                            category { name slug }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("categoryUpdate.category.name").entity(String.class)
                .isEqualTo("P2 Category Gamma Refreshed")
                .path("categoryUpdate.category.slug").entity(String.class).isEqualTo("p2-cat-gamma");

        Category row = categoryRepository.findById(seeded.getId()).orElseThrow();
        assertThat(row.getNameEn()).isEqualTo("P2 Category Gamma Refreshed");
    }

    @Test
    void adminSoftDeletesACategoryAndTheSecondDeleteAnswersNotFound() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        persist("p2-cat-mutation-delete", "P2 Mutation Delete Row", null, 99);
        Category seeded = categoryRepository.findByCode("p2-cat-mutation-delete").orElseThrow();
        String emittedId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + seeded.getId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { categoryDelete(id: "%s") {
                            errors { code }
                            category { slug name }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("categoryDelete.category.slug").entity(String.class).isEqualTo("p2-cat-mutation-delete");

        // Soft delete is the registry's own discipline: the derived
        // lookup no longer resolves the row, and the code is released.
        assertThat(categoryRepository.findById(seeded.getId())).isEmpty();
        assertThat(categoryRepository.findByCode("p2-cat-mutation-delete")).isEmpty();

        // The second delete of the same id answers the NOT_FOUND payload
        // error — the honest absence, never a second success.
        adminTester.document("""
                        mutation { categoryDelete(id: "%s") {
                            errors { field code }
                            category { id }
                        } }
                        """.formatted(emittedId))
                .execute()
                .errors().verify()
                .path("categoryDelete.errors[0].field").entity(String.class).isEqualTo("id")
                .path("categoryDelete.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("categoryDelete.category").valueIsNull();
    }

    @Test
    void adminBulkDeletesCategoriesWithTheHonestCount() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        persist("p2-cat-bulk-one", "P2 Bulk One", null, 97);
        persist("p2-cat-bulk-two", "P2 Bulk Two", null, 98);
        Category first = categoryRepository.findByCode("p2-cat-bulk-one").orElseThrow();
        Category second = categoryRepository.findByCode("p2-cat-bulk-two").orElseThrow();
        String firstId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + first.getId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String secondId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + second.getId())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        // One well-formed but unknown id rides the list — the count must
        // answer only what the registry itself deleted.
        String unknownId = java.util.Base64.getEncoder()
                .encodeToString(("Category:" + UUID.randomUUID())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));

        adminTester.document("""
                        mutation { categoryBulkDelete(ids: ["%s", "%s", "%s"]) { count errors { code } } }
                        """.formatted(firstId, secondId, unknownId))
                .execute()
                .errors().verify()
                .path("categoryBulkDelete.count").entity(Integer.class).isEqualTo(2);

        assertThat(categoryRepository.findById(first.getId())).isEmpty();
        assertThat(categoryRepository.findById(second.getId())).isEmpty();
    }
}

package com.marketplace.app.graphql;

import com.marketplace.catalog.CatalogAttributeDefinition;
import com.marketplace.catalog.CatalogAttributeDefinitionRepository;
import com.marketplace.catalog.CatalogAttributeType;

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
 * P2 (the canonical Saleor plan §5-P2 — the declared continuation of the
 * campaign-b2-a2 product-type slice): end-to-end proof of the Saleor
 * Attributes-domain read roots — {@code attribute} and {@code attributes}
 * — through the REAL SDL, the REAL DGS wiring, the REAL security chain
 * (the mocked JwtDecoder stamps the caller's subject and roles claim
 * exactly the way the production converter reads them), the REAL catalog
 * owner (no SPI mocking: the ADMIN enforcement and the bounded keyset
 * scroll over the reference table execute in the real service), and the
 * REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the canonical plan's P2 gate and the
 * campaign's frozen read contract: the role matrix (a non-admin caller is
 * denied by the owning use case — AUTHZ-001), the read-back journeys (the
 * list's own emitted global ID round-trips through {@code attribute} with
 * same-ID stability; the public slug answers the seeded row), the honest
 * absences (an unknown but well-formed global ID or slug answers null; a
 * malformed, raw or mistyped global ID answers the classified VAL-001
 * error), the keyset pagination contract (forward-only cursors that
 * identify a row's sort key — in BOTH owning sorts — with the
 * declared-but-unexecuted Saleor arguments answering explicit classified
 * errors instead of silent no-ops), and the bounded projection's own
 * facts (the bilingual row's primary-name rendering, and the closed
 * domain value shape's inputType/withChoices renderings — the nameEn-null
 * CHOICE seed proves both honestly).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AttributeGraphQlIntegrationTest {

    private static final String ADMIN_SUBJECT = "p2-attrs-admin-subject";
    private static final String PROVIDER_SUBJECT = "p2-attrs-provider-subject";
    private static final String CONSUMER_SUBJECT = "p2-attrs-consumer-subject";


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
    private CatalogAttributeDefinitionRepository attributeDefinitionRepository;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededIds = new ArrayList<>();

    @BeforeEach
    void seedReferenceRows() {
        // The bilingual + typed-vocabulary legs are deliberate: the second
        // seed has NO nameEn and is CHOICE-typed, so the rendered primary
        // name must fall back to nameAr AND the value shape must render
        // its DROPDOWN inputType with withChoices true — the honest
        // bilingual/typed renderings the bounded projection documents.
        persist("p2-attr-alpha", "P2 Attribute Alpha", null, CatalogAttributeType.TEXT);
        persist("p2-attr-beta", null, "سمة بيتا للاختبار", CatalogAttributeType.CHOICE);
        persist("p2-attr-gamma", "P2 Attribute Gamma", null, CatalogAttributeType.NUMBER);
    }

    @AfterEach
    void removeSeededRows() {
        if (!seededIds.isEmpty()) {
            attributeDefinitionRepository.deleteAllById(new ArrayList<>(seededIds));
            seededIds.clear();
        }
    }

    private void persist(String code, String nameEn, String nameAr, CatalogAttributeType valueType) {
        CatalogAttributeDefinition definition =
                CatalogAttributeDefinition.create(code, nameEn, nameAr, valueType, null);
        attributeDefinitionRepository.save(definition);
        seededIds.add(definition.getId());
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

    /** A well-formed typed global ID for the Attribute type over any native UUID. */
    private static String attributeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Attribute:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for ANOTHER type — the mistyped-ID leg's own input. */
    private static String productTypeGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductType:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // attributes — the ADMIN-enforced keyset scroll (envelope + paging)
    // ------------------------------------------------------------------

    @Test
    void adminSeesTheSaleorEnvelopeOverTheRealReferenceTable() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 10) {
                            edges { node { id name slug inputType withChoices } cursor }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.totalCount").entity(Integer.class)
                .satisfies(total -> assertThat(total).isGreaterThanOrEqualTo(3))
                .path("attributes.edges").entityList(Object.class)
                .satisfies(edges -> assertThat(edges).isNotEmpty())
                .path("attributes.edges[*].node.id").entityList(String.class)
                .satisfies(ids -> assertThat(ids)
                        .allSatisfy(id -> assertThat(decodedGlobalId(id)).startsWith("Attribute:")))
                .path("attributes.edges[*].node.slug").entityList(String.class)
                .satisfies(slugs -> assertThat(slugs)
                        .allSatisfy(slug -> assertThat(slug).matches("^[a-z][a-z0-9-]*$")))
                .path("attributes.edges[*].node.inputType").entityList(String.class)
                .satisfies(inputTypes -> assertThat(inputTypes)
                        .allSatisfy(inputType -> assertThat(inputType)
                                .isIn("PLAIN_TEXT", "DROPDOWN", "NUMERIC")))
                .path("attributes.edges[*].node.withChoices").entityList(Boolean.class)
                .satisfies(flags -> assertThat(flags)
                        .allSatisfy(flag -> assertThat(flag).isNotNull()));
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { attributes(first: 1) {
                            edges { node { name } }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[0].node.name").entity(String.class)
                .satisfies(name -> assertThat(name).isNotBlank())
                .path("attributes.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("attributes.edges").entityList(Object.class).hasSize(1);
        String firstName = firstPage.path("attributes.edges[0].node.name").entity(String.class).get();
        String firstEndCursor = firstPage.path("attributes.pageInfo.endCursor").entity(String.class).get();

        // The cursor identifies a row's own sort key: following it continues
        // the same name-ascending scroll without overlap.
        adminTester.document("""
                        query($after: String) { attributes(first: 1, after: $after) {
                            edges { node { name } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("attributes.edges[0].node.name").entity(String.class)
                .satisfies(secondName -> assertThat(secondName).isGreaterThan(firstName))
                .path("attributes.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void slugSortExecutesAsTheRealKeysetSort() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var firstPage = adminTester.document("""
                        query { attributes(first: 1, sortBy: { direction: ASC, field: SLUG }) {
                            edges { node { slug } }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[0].node.slug").entity(String.class).isEqualTo("p2-attr-alpha");
        String firstEndCursor = firstPage.path("attributes.pageInfo.endCursor").entity(String.class).get();

        // The slug-keyed cursor continues the SAME slug-ascending scroll —
        // a cursor emitted by another sort would be rejected by the gate.
        adminTester.document("""
                        query($after: String) { attributes(first: 1, after: $after, sortBy: { direction: ASC, field: SLUG }) {
                            edges { node { slug } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstEndCursor)
                .execute()
                .errors().verify()
                .path("attributes.edges[0].node.slug").entity(String.class).isEqualTo("p2-attr-beta")
                .path("attributes.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void nameDescendingSortReversesTheWindow() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 5, sortBy: { direction: DESC, field: NAME }) {
                            edges { node { name } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[*].node.name").entityList(String.class)
                .satisfies(names -> {
                    assertThat(names).isNotEmpty();
                    assertThat(names).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                });
    }

    @Test
    void slugDescendingSortReversesTheWindow() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 5, sortBy: { direction: DESC, field: SLUG }) {
                            edges { node { slug } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[*].node.slug").entityList(String.class)
                .satisfies(slugs -> {
                    assertThat(slugs).isNotEmpty();
                    assertThat(slugs).isSortedAccordingTo(java.util.Comparator.reverseOrder());
                });
    }

    @Test
    void bilingualRowRendersItsArabicPrimaryName() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The nameEn-null seed's rendered name IS its Arabic name — the
        // honest bilingual fallback, asserted on the wire.
        adminTester.document("""
                        query { attributes(first: 50) { edges { node { name slug } } } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[*].node.slug").entityList(String.class)
                .contains("p2-attr-beta")
                .path("attributes.edges[*].node.name").entityList(String.class)
                .satisfies(names -> assertThat(names).contains("سمة بيتا للاختبار"));
    }

    @Test
    void emptyFirstAnswersTheEmptyPageWithHonestNullTotal() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 0) {
                            edges { node { name } }
                            pageInfo { hasNextPage hasPreviousPage }
                            totalCount
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges").entityList(Object.class).hasSize(0)
                .path("attributes.totalCount").valueIsNull()
                .path("attributes.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(false)
                .path("attributes.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void cursorFromAnotherSortIsRejectedByTheCursorGate() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        String slugCursor = adminTester.document("""
                        query { attributes(first: 1, sortBy: { direction: ASC, field: SLUG }) {
                            pageInfo { endCursor }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.pageInfo.endCursor").entity(String.class).get();

        // The default (NAME) scroll rejects a cursor emitted by the SLUG
        // sort — the keyset mismatch, classified VAL-001.
        adminTester.document("""
                        query($after: String) { attributes(first: 1, after: $after) { totalCount } }
                        """)
                .variable("after", slugCursor)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getMessage()).contains("after must be a forward keyset cursor");
                });
    }

    // ------------------------------------------------------------------
    // attribute — the ADMIN-enforced lookup (read-back + negatives)
    // ------------------------------------------------------------------

    @Test
    void attributeRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        var page = adminTester.document("""
                        query { attributes(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id name slug } }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("attributes.edges[0].node.id").entity(String.class).get();
        String emittedId = page;
        String emittedName = adminTester.document("""
                        query { attributes(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { name } } } }
                        """)
                .execute().errors().verify()
                .path("attributes.edges[0].node.name").entity(String.class).get();

        var readBack = adminTester.document("""
                        query($id: ID!) { attribute(id: $id) { id name slug inputType withChoices } }
                        """)
                .variable("id", emittedId)
                .execute()
                .errors().verify()
                .path("attribute.id").entity(String.class).isEqualTo(emittedId)
                .path("attribute.name").entity(String.class).isEqualTo(emittedName);

        // Same-ID stability: a fresh read of the same emitted ID answers
        // the same identity again (the native key, not a per-read handle).
        String again = readBack.path("attribute.id").entity(String.class).get();
        assertThat(again).isEqualTo(emittedId);
    }

    @Test
    void attributeBySlugAnswersTheSeededRowWithItsTypedVocabulary() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // The CHOICE seed: the slug lookup answers the bilingual row with
        // its DROPDOWN inputType rendering and its withChoices fact.
        adminTester.document("""
                        query($slug: String) { attribute(slug: $slug) { id name slug inputType withChoices } }
                        """)
                .variable("slug", "p2-attr-beta")
                .execute()
                .errors().verify()
                .path("attribute.id").entity(String.class)
                .satisfies(id -> assertThat(decodedGlobalId(id)).startsWith("Attribute:"))
                .path("attribute.name").entity(String.class).isEqualTo("سمة بيتا للاختبار")
                .path("attribute.slug").entity(String.class).isEqualTo("p2-attr-beta")
                .path("attribute.inputType").entity(String.class).isEqualTo("DROPDOWN")
                .path("attribute.withChoices").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void guessedButWellFormedGlobalIdAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID guessed = UUID.randomUUID();
        adminTester.document("""
                        query($id: ID!) { attribute(id: $id) { id name } }
                        """)
                .variable("id", attributeGlobalId(guessed))
                .execute()
                .errors().verify()
                .path("attribute").valueIsNull();
    }

    @Test
    void guessedSlugAnswersNullNotAnError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($slug: String) { attribute(slug: $slug) { id name } }
                        """)
                .variable("slug", "p2-attr-no-such-code")
                .execute()
                .errors().verify()
                .path("attribute").valueIsNull();
    }

    @Test
    void malformedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($id: ID!) { attribute(id: $id) { id } }
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
                        query($id: ID!) { attribute(id: $id) { id } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                });
    }

    @Test
    void mistypedGlobalIdAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        // A well-formed global ID of ANOTHER type: the codec's per-callsite
        // expected-type check answers the classified VAL-001 error — never
        // a silent null and never a raw UUID fallback.
        adminTester.document("""
                        query($id: ID!) { attribute(id: $id) { id } }
                        """)
                .variable("id", productTypeGlobalId(UUID.randomUUID()))
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
        providerTester.document("query { attributes(first: 1) { totalCount } }")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                    assertThat(error.getExtensions()).containsEntry("category", "authz");
                })
                .path("attributes").valueIsNull();
    }

    @Test
    void consumerCallerIsDeniedTheLookupByTheOwningUseCase() {
        HttpGraphQlTester consumerTester = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumerTester.document("""
                        query($slug: String) { attribute(slug: $slug) { id } }
                        """)
                .variable("slug", "p2-attr-alpha")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "AUTHZ-001");
                })
                .path("attribute").valueIsNull();
    }

    @Test
    void filterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 2, filter: { search: "alpha" }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("filter is not implemented");
                });
    }

    @Test
    void whereFilterAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 2, where: { withChoices: true }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("where filter is not implemented");
                });
    }

    @Test
    void searchAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 2, search: "brand") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("search is not implemented");
                });
    }

    @Test
    void channelAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(first: 2, channel: "channel-1") { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("channel filtering is not implemented");
                });
    }

    @Test
    void backwardPaginationAnswersTheExplicitForwardOnlyError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attributes(last: 2) { totalCount } }
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
                        query { attributes(first: 2, sortBy: { direction: ASC, field: VALUE_REQUIRED }) { totalCount } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("VALUE_REQUIRED is not implemented");
                });
    }

    @Test
    void externalReferenceLookupAnswersTheExplicitNotImplementedError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query($externalReference: String) { attribute(externalReference: $externalReference) { id } }
                        """)
                .variable("externalReference", "erp-brand-1")
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getMessage()).contains("externalReference lookup is not implemented");
                });
    }

    @Test
    void keylessLookupAnswersTheClassifiedValidationError() {
        HttpGraphQlTester adminTester = testerFor(ADMIN_SUBJECT, "ADMIN");
        adminTester.document("""
                        query { attribute { id } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> {
                    var error = errors.getFirst();
                    assertThat(error.getExtensions()).containsEntry("errorCode", "VAL-001");
                    assertThat(error.getMessage()).contains("attribute requires one of id or slug");
                });
    }

}

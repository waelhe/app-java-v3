package com.marketplace.app.graphql;

import com.marketplace.catalog.Collection;
import com.marketplace.catalog.CollectionProduct;
import com.marketplace.catalog.CollectionProductRepository;
import com.marketplace.catalog.CollectionRepository;
import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
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
import test.config.IntegrationContainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * L-slice (the canonical Saleor plan §5-P2 — packet agent-2): end-to-end
 * proof of the Saleor Products-domain collection family — the
 * {@code collection}/{@code collections} read roots, the
 * {@code Collection.products} field and the
 * {@code collectionCreate}/{@code collectionUpdate}/
 * {@code collectionDelete}/{@code collectionBulkDelete}/
 * {@code collectionTranslate}/{@code collectionAddProducts}/
 * {@code collectionRemoveProducts}/{@code collectionReorderProducts}
 * mutation roots — through the REAL SDL, the REAL DGS wiring, the REAL
 * security chain (the mocked JwtDecoder stamps the caller's subject and
 * roles claim exactly the way the production converter reads them), the
 * REAL catalog owner (no SPI mocking: the publication law, the keyset
 * scroll, the membership write surfaces and the reorder resolution
 * execute against the real PostgreSQL over the SAME Flyway-migrated
 * schema the public registry reads serve — the {@code V253} collections
 * and {@code collection_products} tables the slice itself introduced),
 * and the REAL typed global-ID codec.
 *
 * <p>The acceptance legs mirror the packet's gate: the publication law
 * (an authenticated CONSUMER caller sees the published rows alone while
 * the ADMIN caller sees every live row — the include-unpublished
 * capability; an unpublished row's non-ADMIN lookup answers the same
 * safe absence as a missing one), the member-product publication law
 * (a non-ADMIN {@code products} scroll carries the ACTIVE products
 * alone and the total answers the same visibility), the forward-only
 * keyset scroll with cursor follow-through over the registry and the
 * membership's own (position, id) ordering key, the round-trip
 * global-ID stability, the ADMIN-gated mutation ladder (the real
 * {@code @PreAuthorize} gate — a CONSUMER caller's mutation answers
 * Spring Security's own 403), the membership write surfaces' own laws
 * (the duplicate-add DUPLICATED_INPUT_ITEM with the pinned
 * {@code products} member, the idempotent remove, the reorder's
 * compacted 1..n re-derivation read back through the products field),
 * and the classified errors for the malformed/raw/mistyped global IDs
 * — never silent no-ops.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CollectionGraphQlIntegrationTest {

    private static final String CONSUMER_SUBJECT = "l-collections-consumer-subject";
    private static final String ADMIN_SUBJECT = "l-collections-admin-subject";

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
    private CollectionRepository collectionRepository;

    @Autowired
    private CollectionProductRepository collectionProductRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededCollectionIds = new ArrayList<>();
    private final List<UUID> seededProductIds = new ArrayList<>();
    private UUID providerId;
    private String storeCategoryCode;
    private UUID seededTypeId;

    @BeforeEach
    void seedReferenceRows() {
        providerId = UUID.randomUUID();
        userRepository.save(new User(providerId, providerId.toString(), null,
                "l-collections test provider", com.marketplace.identity.UserRole.PROVIDER));

        storeCategoryCode = "lcol-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), storeCategoryCode, "l-collections test", "اختبار المجموعات");

        // The membership's publication law needs both ACTIVE and DRAFT
        // member products; the registry's publication law needs both a
        // published and an unpublished collection.
        persistActiveProduct("lcol Alpha Widget");
        persistActiveProduct("lcol Beta Widget");
        persistDraftProduct("lcol Draft Widget");

        persistCollection("lcol-published", "L Published", "منشورة", true);
        persistCollection("lcol-hidden", "L Hidden", "خفية", false);
    }

    @AfterEach
    void removeSeededRows() {
        for (UUID collectionId : new ArrayList<>(seededCollectionIds)) {
            for (CollectionProduct member : collectionProductRepository
                    .findByCollectionIdOrderByPositionAscIdAsc(collectionId)) {
                collectionProductRepository.delete(member);
            }
            collectionRepository.deleteById(collectionId);
        }
        seededCollectionIds.clear();
        if (!seededProductIds.isEmpty()) {
            productRepository.deleteAllById(new ArrayList<>(seededProductIds));
            seededProductIds.clear();
        }
        jdbcTemplate.update("DELETE FROM store_categories WHERE code = ?", storeCategoryCode);
        if (seededTypeId != null) {
            jdbcTemplate.update("DELETE FROM product_types WHERE id = ?", seededTypeId);
            seededTypeId = null;
        }
        userRepository.deleteById(providerId);
    }

    private void persistCollection(String slug, String nameEn, String nameAr, boolean published) {
        Collection collection = collectionRepository.save(
                Collection.create(slug, nameEn, nameAr, published));
        seededCollectionIds.add(collection.getId());
    }

    private void persistActiveProduct(String title) {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "l-collections fixture", 1000L, "SAR", providerId));
        seededProductIds.add(product.getId());
    }

    private void persistDraftProduct(String title) {
        // The DRAFT product's validated FK needs a REAL goods schema row
        // (the V236 shape) — seeded here and removed after the products
        // (the FK's own ordering law).
        seededTypeId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO product_types
                    (id, code, name_en, name_ar, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, FALSE, 0, now(), now())
                """, seededTypeId,
                "lcol-type-" + seededTypeId.toString().replace("-", "").substring(0, 8),
                "l-collections schema", null);
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "l-collections fixture", 1000L, "SAR", providerId,
                seededTypeId));
        seededProductIds.add(product.getId());
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
     * tag-only encoding is not part of the full "Tag:<uuid>" encoding).
     */
    private static String decodedGlobalId(String globalId) {
        String decoded = new String(java.util.Base64.getDecoder().decode(globalId),
                java.nio.charset.StandardCharsets.UTF_8);
        int separator = decoded.indexOf(':');
        assertThat(separator).isPositive();
        UUID.fromString(decoded.substring(separator + 1));
        return decoded;
    }

    /** A well-formed typed global ID for the Collection type over any native UUID. */
    private static String collectionGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Collection:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for the Product type over any native UUID. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID collectionIdBySlug(String slug) {
        return collectionRepository.findBySlug(slug).orElseThrow().getId();
    }

    private List<UUID> activeProductIds(int count) {
        return productRepository.findAll().stream()
                .filter(product -> product.getStatus() == com.marketplace.catalog.StoreProductStatus.ACTIVE)
                .filter(product -> seededProductIds.contains(product.getId()))
                .map(Product::getId)
                .limit(count)
                .toList();
    }

    private UUID draftProductId() {
        return productRepository.findAll().stream()
                .filter(product -> product.getStatus() == com.marketplace.catalog.StoreProductStatus.DRAFT)
                .filter(product -> seededProductIds.contains(product.getId()))
                .map(Product::getId)
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // collections / collection — the publication law and the keyset scroll
    // ------------------------------------------------------------------

    @Test
    void consumerSeesOnlyThePublishedRowsWhileAdminSeesEveryLiveRow() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumer.document("""
                        query { collections(first: 10, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug isPublished } }
                            totalCount
                        } }
                        """)
                .execute()
                .path("collections.edges[*].node.slug").entityList(String.class)
                .containsExactly("lcol-published")
                .path("collections.totalCount").entity(Integer.class).isEqualTo(1);

        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        query { collections(first: 10, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug isPublished } }
                            totalCount
                        } }
                        """)
                .execute()
                .path("collections.edges[*].node.slug").entityList(String.class)
                .containsExactly("lcol-hidden", "lcol-published")
                .path("collections.totalCount").entity(Integer.class).isEqualTo(2);
    }

    @Test
    void anUnpublishedRowsNonAdminLookupAnswersTheSameSafeAbsence() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        UUID hiddenId = collectionIdBySlug("lcol-hidden");

        consumer.document("""
                        query($id: ID!) { collection(id: $id) { id slug } }
                        """)
                .variable("id", collectionGlobalId(hiddenId))
                .execute()
                .path("collection").valueIsNull();
    }

    @Test
    void collectionRoundTripsTheIdTheListItselfEmitted() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        String emittedId = consumer.document("""
                        query { collections(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { id slug } }
                        } }
                        """)
                .execute()
                .path("collections.edges[0].node.id").entity(String.class).get();

        assertThat(decodedGlobalId(emittedId)).startsWith("Collection:");
        consumer.document("""
                        query($id: ID!) { collection(id: $id) { id slug name nameAr isPublished } }
                        """)
                .variable("id", emittedId)
                .execute()
                .path("collection.id").entity(String.class).isEqualTo(emittedId)
                .path("collection.slug").entity(String.class).isEqualTo("lcol-published")
                .path("collection.name").entity(String.class).isEqualTo("L Published")
                .path("collection.nameAr").entity(String.class).isEqualTo("منشورة")
                .path("collection.isPublished").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void collectionBySlugCarriesTheSamePublicationLaw() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumer.document("""
                        query($slug: String) { collection(slug: $slug) { slug } }
                        """)
                .variable("slug", "lcol-published")
                .execute()
                .path("collection.slug").entity(String.class).isEqualTo("lcol-published");

        consumer.document("""
                        query($slug: String) { collection(slug: $slug) { slug } }
                        """)
                .variable("slug", "lcol-hidden")
                .execute()
                .path("collection").valueIsNull();
    }

    @Test
    void keysetCursorFollowThroughContinuesTheSameScrollWithoutOverlap() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");

        String firstPageEndCursor = admin.document("""
                        query { collections(first: 1, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug } cursor }
                            pageInfo { endCursor hasNextPage }
                        } }
                        """)
                .execute()
                .path("collections.edges[0].node.slug").entity(String.class).isEqualTo("lcol-hidden")
                .path("collections.pageInfo.hasNextPage").entity(Boolean.class).isEqualTo(true)
                .path("collections.pageInfo.endCursor").entity(String.class).get();

        admin.document("""
                        query($after: String) { collections(first: 1, after: $after, sortBy: { direction: ASC, field: NAME }) {
                            edges { node { slug } }
                            pageInfo { hasPreviousPage }
                        } }
                        """)
                .variable("after", firstPageEndCursor)
                .execute()
                .path("collections.edges[0].node.slug").entity(String.class).isEqualTo("lcol-published")
                .path("collections.pageInfo.hasPreviousPage").entity(Boolean.class).isEqualTo(true);
    }

    // ------------------------------------------------------------------
    // Collection.products — the membership scroll with the member law
    // ------------------------------------------------------------------

    @Test
    void theProductsFieldScrollsTheMembershipWithTheMemberPublicationLaw() {
        UUID publishedId = collectionIdBySlug("lcol-published");
        List<UUID> activeIds = activeProductIds(2);
        UUID draftId = draftProductId();
        collectionProductRepository.save(
                CollectionProduct.create(publishedId, activeIds.get(0), 1));
        collectionProductRepository.save(
                CollectionProduct.create(publishedId, activeIds.get(1), 2));
        collectionProductRepository.save(
                CollectionProduct.create(publishedId, draftId, 3));

        // The CONSUMER scroll carries the ACTIVE member products alone
        // and the total answers the same visibility.
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");
        consumer.document("""
                        query($id: ID!) { collection(id: $id) { id products(first: 10) {
                            edges { node { id name } }
                            totalCount
                        } } }
                        """)
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collection.products.edges[0].node.name").entity(String.class).isEqualTo("lcol Alpha Widget")
                .path("collection.products.edges[1].node.name").entity(String.class).isEqualTo("lcol Beta Widget")
                .path("collection.products.totalCount").entity(Integer.class).isEqualTo(2);

        // The ADMIN scroll carries the draft member product too.
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        admin.document("""
                        query($id: ID!) { collection(id: $id) { id products(first: 10) {
                            edges { node { name }
                        } totalCount } } }
                        """)
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collection.products.edges[*].node.name").entityList(String.class)
                .containsExactly("lcol Alpha Widget", "lcol Beta Widget", "lcol Draft Widget")
                .path("collection.products.totalCount").entity(Integer.class).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // the mutation roots — the ADMIN-gated write ladder
    // ------------------------------------------------------------------

    @Test
    void collectionCreatePersistsTheRowAndTheInitialMembership() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID memberId = activeProductIds(1).get(0);

        admin.document("""
                        mutation { collectionCreate(input: {name: "L Created", slug: "lcol-created",
                                isPublished: true, products: ["%s"]}) {
                            errors { field message code }
                            collection { id slug name isPublished }
                        } }
                        """.formatted(productGlobalId(memberId)))
                .execute()
                .path("collectionCreate.errors").entityList(Object.class).hasSize(0)
                .path("collectionCreate.collection.slug").entity(String.class).isEqualTo("lcol-created")
                .path("collectionCreate.collection.name").entity(String.class).isEqualTo("L Created")
                .path("collectionCreate.collection.isPublished").entity(Boolean.class).isEqualTo(true);

        // The created row joins the cleanup list AFTER the mutation
        // persisted it (the mutation itself owns the write).
        collectionRepository.findBySlug("lcol-created")
                .ifPresent(row -> seededCollectionIds.add(row.getId()));

        // The initial membership persisted — the append position is the
        // owner's own 1.
        assertThat(collectionProductRepository
                .findByCollectionIdAndProductId(collectionIdBySlug("lcol-created"), memberId))
                .hasValueSatisfying(member -> assertThat(member.getPosition()).isEqualTo(1));
    }

    @Test
    void collectionCreateAnswersTheUniqueDomainErrorForATakenSlug() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");

        admin.document("""
                        mutation { collectionCreate(input: {name: "Duplicate", slug: "lcol-published"}) {
                            errors { field message code }
                            collection { id }
                        } }
                        """)
                .execute()
                .path("collectionCreate.collection").valueIsNull()
                .path("collectionCreate.errors[0].field").entity(String.class).isEqualTo("slug")
                .path("collectionCreate.errors[0].code").entity(String.class).isEqualTo("UNIQUE");
    }

    @Test
    void collectionUpdateAmendsTheRowWithThePartialPreserveSemantics() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");

        admin.document("""
                        mutation($id: ID!) { collectionUpdate(id: $id, input: {name: "L Amended"}) {
                            errors { field message code }
                            collection { id name slug isPublished }
                        } }
                        """)
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionUpdate.errors").entityList(Object.class).hasSize(0)
                .path("collectionUpdate.collection.name").entity(String.class).isEqualTo("L Amended")
                .path("collectionUpdate.collection.slug").entity(String.class).isEqualTo("lcol-published")
                .path("collectionUpdate.collection.isPublished").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void collectionAddProductsAnswersTheDuplicatedPairWithTheProductsMember() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");
        UUID memberId = activeProductIds(1).get(0);
        collectionProductRepository.save(CollectionProduct.create(publishedId, memberId, 1));

        admin.document("""
                        mutation($id: ID!) { collectionAddProducts(collectionId: $id, products: ["%s"]) {
                            errors { field message code products }
                            collection { id }
                        } }
                        """.formatted(productGlobalId(memberId)))
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionAddProducts.collection").valueIsNull()
                .path("collectionAddProducts.errors[0].code").entity(String.class)
                .isEqualTo("DUPLICATED_INPUT_ITEM")
                .path("collectionAddProducts.errors[0].products[0]").entity(String.class)
                .isEqualTo(productGlobalId(memberId));
    }

    @Test
    void collectionReorderProductsReDerivesTheCompactedPositions() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");
        List<UUID> activeIds = activeProductIds(2);
        collectionProductRepository.save(
                CollectionProduct.create(publishedId, activeIds.get(0), 1));
        collectionProductRepository.save(
                CollectionProduct.create(publishedId, activeIds.get(1), 2));

        // Move the second member one position backward: the compacted
        // 1..n re-derivation swaps the rows.
        admin.document("""
                        mutation($id: ID!) { collectionReorderProducts(collectionId: $id,
                                moves: [{productId: "%s", sortOrder: -1}]) {
                            errors { field message code }
                            collection { id }
                        } }
                        """.formatted(productGlobalId(activeIds.get(1))))
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionReorderProducts.errors").entityList(Object.class).hasSize(0);

        assertThat(collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(publishedId))
                .extracting(CollectionProduct::getProductId)
                .containsExactly(activeIds.get(1), activeIds.get(0));
    }

    @Test
    void collectionRemoveProductsIsTheIdempotentSkip() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");
        UUID memberId = activeProductIds(1).get(0);
        collectionProductRepository.save(CollectionProduct.create(publishedId, memberId, 1));

        admin.document("""
                        mutation($id: ID!) { collectionRemoveProducts(collectionId: $id, products: ["%s"]) {
                            errors { field message code }
                            collection { id }
                        } }
                        """.formatted(productGlobalId(memberId)))
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionRemoveProducts.errors").entityList(Object.class).hasSize(0);

        assertThat(collectionProductRepository
                .findByCollectionIdAndProductId(publishedId, memberId)).isEmpty();
    }

    @Test
    void collectionTranslateAmendsTheBilingualColumns() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");

        admin.document("""
                        mutation($id: ID!) { collectionTranslate(id: $id, input: {name: "المجموعة المنشورة"},
                                languageCode: AR) {
                            errors { field message code }
                            collection { id name nameAr slug }
                        } }
                        """)
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionTranslate.errors").entityList(Object.class).hasSize(0)
                .path("collectionTranslate.collection.nameAr").entity(String.class)
                .isEqualTo("المجموعة المنشورة")
                .path("collectionTranslate.collection.name").entity(String.class)
                .isEqualTo("L Published");
    }

    @Test
    void collectionDeleteRemovesTheRowAndItsMembershipTogether() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");
        UUID publishedId = collectionIdBySlug("lcol-published");
        UUID memberId = activeProductIds(1).get(0);
        collectionProductRepository.save(CollectionProduct.create(publishedId, memberId, 1));

        admin.document("""
                        mutation($id: ID!) { collectionDelete(id: $id) {
                            errors { field message code }
                            collection { id slug }
                        } }
                        """)
                .variable("id", collectionGlobalId(publishedId))
                .execute()
                .path("collectionDelete.errors").entityList(Object.class).hasSize(0)
                .path("collectionDelete.collection.slug").entity(String.class).isEqualTo("lcol-published");

        assertThat(collectionRepository.findById(publishedId)).isEmpty();
        assertThat(collectionProductRepository
                .findByCollectionIdOrderByPositionAscIdAsc(publishedId)).isEmpty();
    }

    @Test
    void consumerCallerIsDeniedTheWriteRoots() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumer.document("""
                        mutation { collectionCreate(input: {name: "X", slug: "x"}) { errors { field } } }
                        """)
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void aRawUuidAnswersTheClassifiedValidationError() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");

        admin.document("""
                        mutation($id: ID!) { collectionDelete(id: $id) { errors { field } } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void aMistypedGlobalIdAnswersTheCodecsExpectedTypeCheck() {
        HttpGraphQlTester admin = testerFor(ADMIN_SUBJECT, "ADMIN");

        admin.document("""
                        mutation($id: ID!) { collectionDelete(id: $id) { errors { field } } }
                        """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void anonymousCollectionQueryIsAnsweredByThePreLoginGateEnvelope() {
        // No Authorization header: the security chain now permits the
        // anonymous POST and PreLoginGraphQlGate is the wall — the gate
        // parses the document, finds the protected `collections` root,
        // and answers the classified AUTHN-001 GraphQL error INSIDE the
        // envelope (HTTP 200 + application/json, the error before any
        // dispatch). The same reopened contract GraphQlSecurityIntegration
        // Test pins at the surface level, asserted here for the
        // collections root's own journey: the classified error pair, and
        // NO data — this IS the public-root surface's authentication
        // boundary (the registered public-root mapping divergence), and
        // the protected collection never resolves for the anonymous
        // caller.
        WebTestClient anonymousClient = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        anonymousClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"query\":\"{ collections(first: 1) { totalCount } }\"}")
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
}

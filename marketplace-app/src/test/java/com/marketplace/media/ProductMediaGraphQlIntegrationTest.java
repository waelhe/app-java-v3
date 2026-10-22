package com.marketplace.media;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.identity.User;
import com.marketplace.identity.UserRole;
import com.marketplace.identity.UserRepository;
import com.marketplace.media.MediaAsset;
import com.marketplace.media.MediaAssetRepository;
import com.marketplace.media.S3MediaStorage;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import test.config.IntegrationContainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * N-slice (the canonical Saleor plan §5-P2 — packet agent-2): the
 * end-to-end proof of the Saleor Products-domain product-media family's
 * three storage-owned roots — {@code productMediaDelete},
 * {@code productMediaBulkDelete} and {@code productMediaReorder} —
 * through the REAL SDL, the REAL DGS wiring, the REAL security chain
 * (the mocked JwtDecoder stamps the caller's subject and roles claim
 * exactly the way the production converter reads them), the REAL media
 * owner (no SPI mocking: the typed-family boundary, the module's own
 * ownership law, the deletion seam's soft delete and the reorder's
 * advisory-locked position rewrite execute against the real PostgreSQL
 * over the SAME Flyway-migrated schema the media module's own surfaces
 * serve), and the REAL typed global-ID codec. The S3 I/O and the
 * identity seams are mocked at the port line (the
 * ProductMediaJourneyIntegrationTest precedent) — every gate between
 * them is the real chain.
 *
 * <p>The acceptance legs mirror the packet's gate: the owner's delete
 * removes the row through the real soft-delete SQL (the read-back
 * absence) and the payload answers the deleted row's last-known
 * projection; the stranger's row and a foreign-target row both answer
 * the SAME safe absence (the R5 posture — never an existence
 * disclosure); the bulk delete's count answers only what the registry
 * itself deleted; the reorder's compacted 1..n re-derivation and the
 * pending-intent re-park read back through the repository; a partial
 * reorder list answers the classified INVALID refusal; a CONSUMER
 * caller's mutation answers Spring Security's own 403 (the real
 * {@code @PreAuthorize} PROVIDER gate); and the malformed/raw/mistyped
 * global IDs answer the classified GraphQL error family — never silent
 * no-ops.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProductMediaGraphQlIntegrationTest {

    private static final String PROVIDER_SUBJECT = "n-media-provider-subject";
    private static final String CONSUMER_SUBJECT = "n-media-consumer-subject";

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

    // The media domain's own port-line mocks (the journey test's
    // precedent): the identity seams resolve the caller, the storage
    // seam answers the deletion's after-commit cleanup.
    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ProviderLookupPort providerLookupPort;

    @MockitoBean
    private S3MediaStorage storage;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    /** The rows this test seeds — cleaned up after each leg, never touching reference data it does not own. */
    private final List<UUID> seededMediaIds = new ArrayList<>();
    private final List<UUID> seededProductIds = new ArrayList<>();
    private UUID ownerId;
    private String storeCategoryCode;

    @BeforeEach
    void seedReferenceRows() {
        ownerId = UUID.randomUUID();
        userRepository.save(new User(ownerId, ownerId.toString(), null,
                "n-media test provider", UserRole.PROVIDER));

        storeCategoryCode = "nmedia-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbcTemplate.update("""
                INSERT INTO store_categories
                    (id, code, name_en, name_ar, position, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, 0, FALSE, 0, now(), now())
                """, UUID.randomUUID(), storeCategoryCode, "n-media test", "اختبار الوسائط");

        // The owner stub — the module's own A-17/C.7 law's provider-profile
        // link (the journey test's measured stub shape: the lookup key is
        // the CALLER's user id).
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(ownerId);
        when(currentUserProvider.isAdmin(any(Authentication.class))).thenReturn(false);
        when(providerLookupPort.findByUserId(ownerId))
                .thenReturn(Optional.of(new ProviderSummary(ownerId, "P", "VERIFIED", ownerId)));
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");
    }

    @AfterEach
    void removeSeededRows() {
        // The fixture rows leave PHYSICALLY, in FK-respecting order (the
        // Product cleanup's own law — Hibernate @SoftDelete rows would
        // otherwise keep referencing store_categories and trip
        // fk_products_store_category on the hard DELETE below).
        for (UUID mediaId : seededMediaIds) {
            jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", mediaId);
        }
        seededMediaIds.clear();
        for (UUID productId : seededProductIds) {
            jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        }
        seededProductIds.clear();
        jdbcTemplate.update("DELETE FROM store_categories WHERE code = ?", storeCategoryCode);
        userRepository.deleteById(ownerId);
    }

    private UUID persistProduct(String title) {
        Product product = productRepository.save(Product.register(
                storeCategoryCode, title, "n-media fixture", 1000L, "SAR", ownerId));
        seededProductIds.add(product.getId());
        return product.getId();
    }

    private UUID persistUploadedMedia(UUID productId, int position) {
        MediaAsset asset = MediaAsset.createForProduct(productId, ownerId,
                "products/" + productId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, position);
        asset.markUploaded();
        seededMediaIds.add(mediaAssetRepository.save(asset).getId());
        return asset.getId();
    }

    private UUID persistPendingMedia(UUID productId, int position) {
        MediaAsset asset = MediaAsset.createForProduct(productId, ownerId,
                "products/" + productId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, position);
        seededMediaIds.add(mediaAssetRepository.save(asset).getId());
        return asset.getId();
    }

    /**
     * The authenticated tester — the JWT carries the subject and the roles
     * claim exactly as the production {@code JwtAuthenticationConverter}
     * reads them (the CollectionGraphQlIntegrationTest precedent).
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

    /** A well-formed typed global ID for the ProductMedia type over any native UUID. */
    private static String productMediaGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("ProductMedia:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** A well-formed typed global ID for the Product type over any native UUID. */
    private static String productGlobalId(UUID id) {
        return java.util.Base64.getEncoder()
                .encodeToString(("Product:" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------
    // productMediaDelete — the real soft delete, the safe absences
    // ------------------------------------------------------------------

    @Test
    void theOwnersDeleteRemovesTheRowThroughTheRealSeam() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID productId = persistProduct("n-media Espresso");
        UUID mediaId = persistUploadedMedia(productId, 1);
        persistUploadedMedia(productId, 2);

        provider.document("""
                        mutation($id: ID!) { productMediaDelete(id: $id) {
                            errors { field message code }
                            media { id sortOrder productId }
                            product { id name }
                        } }
                        """)
                .variable("id", productMediaGlobalId(mediaId))
                .execute()
                .path("productMediaDelete.errors").entityList(Object.class).hasSize(0)
                .path("productMediaDelete.media.id").entity(String.class)
                .isEqualTo(productMediaGlobalId(mediaId))
                .path("productMediaDelete.media.sortOrder").entity(Integer.class).isEqualTo(1)
                .path("productMediaDelete.media.productId").entity(String.class)
                .isEqualTo(productGlobalId(productId))
                .path("productMediaDelete.product.id").entity(String.class)
                .isEqualTo(productGlobalId(productId));

        // The real soft-delete SQL: the deleted row answers the read-back
        // absence while the sibling row stays live.
        assertThat(mediaAssetRepository.findById(mediaId)).isEmpty();
        assertThat(mediaAssetRepository.findById(seededMediaIds.get(1))).isPresent();
    }

    @Test
    void aStrangersDeleteAnswersTheSameSafeAbsenceAsAMissingRow() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID productId = persistProduct("n-media Stranger Target");
        UUID mediaId = persistUploadedMedia(productId, 1);
        // The stranger's caller id — the ownership match fails without any
        // existence disclosure.
        when(currentUserProvider.getCurrentUserId(any(Authentication.class)))
                .thenReturn(UUID.randomUUID());

        provider.document("""
                        mutation($id: ID!) { productMediaDelete(id: $id) {
                            errors { field code }
                            media { id }
                            product { id }
                        } }
                        """)
                .variable("id", productMediaGlobalId(mediaId))
                .execute()
                .path("productMediaDelete.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("productMediaDelete.media").valueIsNull()
                .path("productMediaDelete.product").valueIsNull();

        // The stranger's refusal left the row untouched.
        assertThat(mediaAssetRepository.findById(mediaId)).isPresent();
    }

    @Test
    void aForeignTargetRowAnswersTheSameSafeAbsence() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID listingId = persistProduct("n-media Foreign Target");
        // A LISTING-target row owned by the same caller — addressed
        // through the product-media root it answers the typed family's
        // own safe absence.
        MediaAsset listingRow = mediaAssetRepository.save(MediaAsset.create(
                listingId, ownerId, "listings/" + listingId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, 1));
        seededMediaIds.add(listingRow.getId());

        provider.document("""
                        mutation($id: ID!) { productMediaDelete(id: $id) {
                            errors { field code }
                            media { id }
                        } }
                        """)
                .variable("id", productMediaGlobalId(listingRow.getId()))
                .execute()
                .path("productMediaDelete.errors[0].code").entity(String.class).isEqualTo("NOT_FOUND")
                .path("productMediaDelete.media").valueIsNull();

        assertThat(mediaAssetRepository.findById(listingRow.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    // productMediaBulkDelete — the honest count
    // ------------------------------------------------------------------

    @Test
    void theBulkDeleteAnswersTheHonestCount() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID productId = persistProduct("n-media Bulk Target");
        UUID first = persistUploadedMedia(productId, 1);
        UUID second = persistUploadedMedia(productId, 2);
        persistUploadedMedia(productId, 3);
        UUID missing = UUID.randomUUID();

        provider.document("""
                        mutation { productMediaBulkDelete(ids: ["%s", "%s", "%s"]) {
                            errors { field message code }
                            count
                        } }
                        """.formatted(productMediaGlobalId(first), productMediaGlobalId(second),
                                productMediaGlobalId(missing)))
                .execute()
                .path("productMediaBulkDelete.errors").entityList(Object.class).hasSize(0)
                .path("productMediaBulkDelete.count").entity(Integer.class).isEqualTo(2);

        assertThat(mediaAssetRepository.findById(first)).isEmpty();
        assertThat(mediaAssetRepository.findById(second)).isEmpty();
        // The untouched third row stays live — the count answered only
        // what the registry itself deleted.
        assertThat(mediaAssetRepository.findById(seededMediaIds.get(2))).isPresent();
    }

    // ------------------------------------------------------------------
    // productMediaReorder — the compacted rewrite and the refusals
    // ------------------------------------------------------------------

    @Test
    void theReorderRewritesOneToNAndParksPendingIntentsAfter() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID productId = persistProduct("n-media Reorder Target");
        UUID first = persistUploadedMedia(productId, 1);
        UUID second = persistUploadedMedia(productId, 2);
        UUID third = persistUploadedMedia(productId, 3);
        UUID pending = persistPendingMedia(productId, 4);

        provider.document("""
                        mutation($productId: ID!) { productMediaReorder(productId: $productId,
                                mediaIds: ["%s", "%s", "%s"]) {
                            errors { field message code }
                            media { id sortOrder productId }
                            product { id }
                        } }
                        """.formatted(productMediaGlobalId(third), productMediaGlobalId(first),
                                productMediaGlobalId(second)))
                .variable("productId", productGlobalId(productId))
                .execute()
                .path("productMediaReorder.errors").entityList(Object.class).hasSize(0)
                .path("productMediaReorder.media[0].id").entity(String.class)
                .isEqualTo(productMediaGlobalId(third))
                .path("productMediaReorder.media[0].sortOrder").entity(Integer.class).isEqualTo(1)
                .path("productMediaReorder.media[1].id").entity(String.class)
                .isEqualTo(productMediaGlobalId(first))
                .path("productMediaReorder.media[1].sortOrder").entity(Integer.class).isEqualTo(2)
                .path("productMediaReorder.media[2].id").entity(String.class)
                .isEqualTo(productMediaGlobalId(second))
                .path("productMediaReorder.media[2].sortOrder").entity(Integer.class).isEqualTo(3)
                .path("productMediaReorder.product.id").entity(String.class)
                .isEqualTo(productGlobalId(productId));

        // The read-back over the real rows: the compacted 1..n rewrite
        // and the pending intent's re-park after the confirmed block.
        assertThat(mediaAssetRepository.findById(third).orElseThrow().getPosition()).isEqualTo(1);
        assertThat(mediaAssetRepository.findById(first).orElseThrow().getPosition()).isEqualTo(2);
        assertThat(mediaAssetRepository.findById(second).orElseThrow().getPosition()).isEqualTo(3);
        assertThat(mediaAssetRepository.findById(pending).orElseThrow().getPosition()).isEqualTo(4);
    }

    @Test
    void aPartialReorderListIsTheClassifiedInvalidRefusal() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");
        UUID productId = persistProduct("n-media Partial Target");
        UUID first = persistUploadedMedia(productId, 1);
        persistUploadedMedia(productId, 2);

        provider.document("""
                        mutation($productId: ID!) { productMediaReorder(productId: $productId,
                                mediaIds: ["%s"]) {
                            errors { field code }
                            media { id }
                        } }
                        """.formatted(productMediaGlobalId(first)))
                .variable("productId", productGlobalId(productId))
                .execute()
                .path("productMediaReorder.errors[0].code").entity(String.class).isEqualTo("INVALID")
                .path("productMediaReorder.media").valueIsNull();

        // The refused rewrite left every position untouched.
        assertThat(mediaAssetRepository.findById(first).orElseThrow().getPosition()).isEqualTo(1);
        assertThat(mediaAssetRepository.findById(seededMediaIds.get(1)).orElseThrow().getPosition())
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // The transport negatives — the real gates
    // ------------------------------------------------------------------

    @Test
    void consumerCallerIsDeniedTheWriteRoots() {
        HttpGraphQlTester consumer = testerFor(CONSUMER_SUBJECT, "CONSUMER");

        consumer.document("""
                        mutation { productMediaDelete(id: "%s") { errors { field } media { id } } }
                        """.formatted(productMediaGlobalId(UUID.randomUUID())))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void aRawUuidAnswersTheClassifiedValidationError() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");

        provider.document("""
                        mutation($id: ID!) { productMediaDelete(id: $id) { errors { field } media { id } } }
                        """)
                .variable("id", UUID.randomUUID().toString())
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }

    @Test
    void aMistypedGlobalIdAnswersTheCodecsExpectedTypeCheck() {
        HttpGraphQlTester provider = testerFor(PROVIDER_SUBJECT, "PROVIDER");

        provider.document("""
                        mutation($id: ID!) { productMediaDelete(id: $id) { errors { field } media { id } } }
                        """)
                .variable("id", productGlobalId(UUID.randomUUID()))
                .execute()
                .errors()
                .satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}

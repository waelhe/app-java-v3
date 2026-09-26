package com.marketplace.media;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S5 (comprehensive repair plan §10/2.2): the integration guard for the
 * listing's media read being PUBLIC — through the REAL resource-server
 * chain, on the REAL Flyway schema:
 * <ol>
 *   <li><b>The S5 core:</b> an anonymous caller reads the published
 *       listing's media (200, the asset's presigned URL present) — the same
 *       visibility the public listings GET line grants the listing itself.
 *       Before the fix there was no media matcher at all and this request
 *       answered 401 while the listing itself was public.</li>
 *   <li><b>The boundary holds:</b> the media WRITE surfaces stay
 *       authenticated — an anonymous upload declaration answers 401 (the
 *       line is GET-scoped and listing-subresource-scoped).</li>
 *   <li><b>Listing isolation:</b> a listing with only PENDING_UPLOAD assets
 *       answers an empty array (UPLOADED visibility only, the documented
 *       contract).</li>
 * </ol>
 *
 * <p>Boot pattern follows {@code ListingCompletenessIntegrationTest}: the
 * isolated postgis container via {@code @ServiceConnection}, Flyway enabled,
 * {@code ddl-auto=none}, plain {@code JdbcTemplate} fixtures. The S3 storage
 * beans register from dummy credentials (presigning is client-side signing —
 * no network I/O — and the empty-listing case never reaches the storage at
 * all).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The S3 beans' registration condition (MediaStorageConfiguredCondition):
        // dummy values — presigned URL generation is offline SigV4 signing.
        "marketplace.media.storage.endpoint=https://s3.test.example",
        "marketplace.media.storage.bucket=it-media-bucket",
        "marketplace.media.storage.access-key=it-media-access",
        "marketplace.media.storage.secret-key=it-media-secret",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class MediaPublicReadIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID PUBLISHED_LISTING_ID = UUID.randomUUID();
    private static final UUID PENDING_ONLY_LISTING_ID = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                OWNER_USER_ID, "s5-owner-" + OWNER_USER_ID,
                "s5-owner-" + OWNER_USER_ID + "@example.com", "S5 Owner");
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, ?, 'S5 Published', 'photos for the guest', 'APARTMENT', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)",
                PUBLISHED_LISTING_ID, OWNER_USER_ID);
        jdbcTemplate.update(
                "INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, ?, 'S5 Pending', 'no uploaded photos yet', 'APARTMENT', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)",
                PENDING_ONLY_LISTING_ID, OWNER_USER_ID);
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, listing_id, provider_id, object_key, content_type, size_bytes, status, position, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 2048, 'UPLOADED', 0, now(), now(), 0, false)",
                UUID.randomUUID(), PUBLISHED_LISTING_ID, OWNER_USER_ID,
                "listings/" + PUBLISHED_LISTING_ID + "/s5-photo.jpg");
        jdbcTemplate.update(
                "INSERT INTO media_assets (id, listing_id, provider_id, object_key, content_type, size_bytes, status, position, created_at, updated_at, version, is_deleted) "
                        + "VALUES (?, ?, ?, ?, 'image/jpeg', 2048, 'PENDING_UPLOAD', 0, now(), now(), 0, false)",
                UUID.randomUUID(), PENDING_ONLY_LISTING_ID, OWNER_USER_ID,
                "listings/" + PENDING_ONLY_LISTING_ID + "/s5-pending.jpg");
    }

    @Test
    void anonymousGuestReadsThePublishedListingsMedia() throws Exception {
        // The S5 core: the same visibility the public listing itself has.
        mockMvc.perform(get("/api/v1/media/listings/{listingId}", PUBLISHED_LISTING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].downloadUrl").isNotEmpty())
                .andExpect(jsonPath("$[0].contentType").value("image/jpeg"));
    }

    @Test
    void anonymousUploadDeclarationStaysUnauthorized() throws Exception {
        // The boundary holds: the WRITE surface keeps its authenticated
        // contract — the new line is GET-scoped, listing-subresource-scoped.
        mockMvc.perform(post("/api/v1/media/uploads")
                        .contentType("application/json")
                        .content("""
                                {"listingId": "%s", "contentType": "image/jpeg", "sizeBytes": 2048}
                                """.formatted(PUBLISHED_LISTING_ID)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingUploadOnlyListingAnswersAnEmptyArray() throws Exception {
        // UPLOADED visibility only — the documented contract, now for the
        // guest too.
        mockMvc.perform(get("/api/v1/media/listings/{listingId}", PENDING_ONLY_LISTING_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}

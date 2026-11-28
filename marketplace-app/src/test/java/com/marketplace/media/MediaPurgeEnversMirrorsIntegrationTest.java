package com.marketplace.media;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G3/833-05 (the 2026-11-28 review, P3 — rulebook B5 "base tables + Envers
 * mirrors"): the media purge family's contract measured on a real database —
 * a completed purge moves the captured rows to the terminal {@code PURGED}
 * state <b>and</b> removes the Envers mirrors of the erased subject in the
 * same transaction that writes PURGED. The reviews adapter's own
 * {@code reviews_aud} scrub and b-4's wholesale {@code users_aud} deletion
 * are the family precedent; leaving the media mirrors behind would retain
 * the erased subject's last revision metadata (object keys, owner id)
 * indefinitely.
 *
 * <p>Row witnesses are created through the repositories so Envers writes the
 * {@code media_assets_aud} / {@code review_media_aud} revisions for real;
 * the storage boundary is the {@code @MockitoBean} (delete succeeds as a
 * no-op at the mocked SDK boundary, the MediaUploadFlowIntegrationTest
 * shape). The regression pin must FAIL on the pre-fix code: the mirrors
 * survived the purge there.</p>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(test.config.ModuleTestConfig.class)
class MediaPurgeEnversMirrorsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** The storage boundary — the purge's tier-2 delete succeeds as a no-op here. */
    @MockitoBean
    S3MediaStorage storage;

    @Autowired
    private com.marketplace.media.spi.MediaPurgeAdapter mediaPurgeAdapter;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private ReviewMediaRepository reviewMediaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aCompletedPurgeRemovesTheEnversMirrorsWithTheTerminalState() {
        UUID ownerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();

        // Seeded through the repositories so Envers writes the mirrors for
        // real (the entity path, not hand-seeded audit rows).
        MediaAsset asset = mediaAssetRepository.save(MediaAsset.create(
                listingId, ownerId,
                "listings/" + listingId + "/mirrors.jpg",
                "media-upload-staging/" + UUID.randomUUID(),
                "image/jpeg", 128L, 1));
        ReviewMedia reviewMedia = reviewMediaRepository.save(ReviewMedia.create(
                reviewId, ownerId,
                "reviews/" + reviewId + "/mirrors.jpg",
                "media-upload-staging/" + UUID.randomUUID(),
                "image/jpeg", 64L, 1));

        // The witnesses: both mirrors exist before the purge.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_assets_aud WHERE provider_id = ?",
                Integer.class, ownerId))
                .as("the seeded asset's Envers mirror exists before the purge")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_media_aud WHERE uploader_id = ?",
                Integer.class, ownerId))
                .as("the seeded review-media's Envers mirror exists before the purge")
                .isEqualTo(1);
        Long assetVersionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM media_assets WHERE id = ?", Long.class, asset.getId());

        int purgedRows = mediaPurgeAdapter.purgeAuthoredTexts(ownerId);

        // The terminal state + the purge count.
        assertThat(purgedRows)
                .as("the purge reports both captured rows")
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM media_assets WHERE id = ?", String.class, asset.getId()))
                .as("the asset reached the terminal PURGED state")
                .isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM review_media WHERE id = ?", String.class, reviewMedia.getId()))
                .as("the review media reached the terminal PURGED state")
                .isEqualTo("PURGED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM media_assets WHERE id = ?", Long.class, asset.getId()))
                .as("rulebook B5: the purge statement advances the row version")
                .isEqualTo(assetVersionBefore + 1);

        // The 833-05 law itself: the mirrors left with the terminal state.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_assets_aud WHERE provider_id = ?",
                Integer.class, ownerId))
                .as("the erased subject's media_assets_aud mirror left with the PURGED state")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM review_media_aud WHERE uploader_id = ?",
                Integer.class, ownerId))
                .as("the erased subject's review_media_aud mirror left with the PURGED state")
                .isZero();
    }
}

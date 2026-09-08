package com.marketplace.media;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The full happy-path flow against a real database (test profile: create-drop
 * schema from the entity mappings) with the storage channel mocked at its SDK
 * boundary: request a presigned upload, confirm after storage verification,
 * read back through the listing, delete. The repository, entity lifecycle,
 * Envers auditing and the object ownership rules all run for real.
 *
 * <p><b>Per-method identities:</b> {@code @ApplicationModuleTest} is
 * meta-annotated {@code @TestInstance(PER_CLASS)} (verified in the official
 * spring-modulith-test 2.1.1 bytecode) — ONE instance serves every method, and
 * the database is shared without rollback. Test identities are therefore
 * allocated INSIDE each method, never as instance fields.
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(test.config.ModuleTestConfig.class)
@WithMockUser(roles = "PROVIDER")
class MediaUploadFlowIntegrationTest {

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    ListingPriceProvider listingPriceProvider;

    @MockitoBean
    ProviderLookupPort providerLookupPort;

    @MockitoBean
    S3MediaStorage storage;

    @Autowired
    private MediaService mediaService;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    /**
     * Stubs ownership for the media flow: the user-owned provider profile
     * ({@code findByUserId}, A1) plus the listing price info.
     */
    private void mockOwner(UUID userId, UUID providerId, UUID listingId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
    }

    /**
     * L28: a small (320×200) valid JPEG — within the default 640 bound, so
     * the thumbnail pipeline keeps the original as its own thumb.
     */
    private static byte[] smallJpeg() {
        try {
            java.awt.image.BufferedImage image =
                    new java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "jpeg", out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("test fixture failed to encode a JPEG", ex);
        }
    }

    @Test
    void requestConfirmListDelete_fullLifecycle() {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);
        when(storage.presignUpload(anyString(), anyString()))
                .thenReturn("https://storage.example/signed-put");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(true);
        when(storage.presignDownload(anyString()))
                .thenReturn("https://storage.example/signed-get");
        // L28: the confirm call now publishes MediaUploadedEvent — the async
        // listener will fetch the object; stub it with a small valid JPEG so
        // the background pipeline succeeds quietly (no duplicate stored,
        // thumb = original within the bound).
        when(storage.getObject(anyString())).thenReturn(smallJpeg());

        // 1) request: row persisted PENDING with server-generated key
        var view = mediaService.requestUpload(listingId, "image/jpeg", 2048L, null);
        assertThat(view.uploadUrl()).isEqualTo("https://storage.example/signed-put");
        assertThat(view.objectKey()).startsWith("listings/" + listingId + "/").endsWith(".jpg");
        assertThat(view.urlLifetime()).isEqualTo(Duration.ofMinutes(15));

        var persisted = mediaAssetRepository.findById(view.mediaId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MediaAssetStatus.PENDING_UPLOAD);
        assertThat(persisted.getListingId()).isEqualTo(listingId);
        assertThat(persisted.getPosition()).isEqualTo(1);

        // 2) confirm: verified by storage, transitions to UPLOADED — the
        // response's thumbUrl is null by contract (the listener runs AFTER_COMMIT)
        var confirmed = mediaService.confirmUpload(view.mediaId(), null);
        assertThat(confirmed.status()).isEqualTo("UPLOADED");
        assertThat(confirmed.downloadUrl()).isEqualTo("https://storage.example/signed-get");
        assertThat(confirmed.thumbUrl()).isNull();
        assertThat(mediaAssetRepository.findById(view.mediaId()).orElseThrow().getStatus())
                .isEqualTo(MediaAssetStatus.UPLOADED);

        // 3) read path: only UPLOADED assets, presigned per call — thumbUrl
        // still null until processing runs (L28)
        var listing = mediaService.listByListing(listingId);
        assertThat(listing).hasSize(1);
        assertThat(listing.get(0).id()).isEqualTo(view.mediaId());
        assertThat(listing.get(0).thumbUrl()).isNull();

        // 3b) L28: process the thumbnail (the listener's command, called
        // directly for determinism) — the small original keeps itself as
        // thumb, and the read then returns both links.
        mediaService.processThumbnail(view.mediaId());
        var afterProcessing = mediaService.listByListing(listingId);
        assertThat(afterProcessing.get(0).thumbUrl())
                .isEqualTo("https://storage.example/signed-get");
        assertThat(mediaAssetRepository.findById(view.mediaId()).orElseThrow()
                .getThumbObjectKey()).isEqualTo(view.objectKey());

        // 4) delete: soft-deleted record, storage object removed best-effort
        mediaService.delete(view.mediaId(), null);
        assertThat(mediaAssetRepository.findById(view.mediaId())).isEmpty();
        assertThat(mediaService.listByListing(listingId)).isEmpty();
    }

    @Test
    void secondAssetGetsNextPosition() {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");
        when(storage.presignDownload(anyString())).thenReturn("https://g");

        var first = mediaService.requestUpload(listingId, "image/png", 100L, null);
        var second = mediaService.requestUpload(listingId, "image/png", 100L, null);

        assertThat(mediaAssetRepository.findById(first.mediaId()).orElseThrow().getPosition()).isEqualTo(1);
        assertThat(mediaAssetRepository.findById(second.mediaId()).orElseThrow().getPosition()).isEqualTo(2);
    }

    @Test
    void concurrentUploadsForTheSameListingGetDistinctPositions() throws Exception {
        // CodeRabbit #241: countByListingId()+1 inside a transaction does not
        // serialize concurrent transactions — four simultaneous uploads for
        // one listing would read the same count and persist duplicate
        // positions. The pg_advisory_xact_lock in the repository makes the
        // allocation exclusive per listing; this test fails without it (with
        // high probability at 4 racers) and always passes with it.
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");

        // @WithMockUser binds the SecurityContext to the TEST thread only —
        // the worker threads must carry the same authentication through the
        // @PreAuthorize gate.
        org.springframework.security.core.Authentication authentication =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).as("the class-level @WithMockUser must be active").isNotNull();

        int racers = 4;
        var startGate = new java.util.concurrent.CountDownLatch(1);
        // CodeRabbit #242 round 2: without a ready gate, startGate.countDown()
        // can fire before every worker reaches await() — the uploads then run
        // serially and the test passes without exercising the race at all.
        var readyGate = new java.util.concurrent.CountDownLatch(racers);
        var ids = new java.util.ArrayList<java.util.concurrent.Future<UUID>>();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(racers)) {
            for (int i = 0; i < racers; i++) {
                ids.add(pool.submit(() -> {
                    try {
                        org.springframework.security.core.context.SecurityContextHolder.setContext(
                                org.springframework.security.core.context.SecurityContextHolder
                                        .createEmptyContext());
                        org.springframework.security.core.context.SecurityContextHolder.getContext()
                                .setAuthentication(authentication);
                        readyGate.countDown();
                        startGate.await();
                        return mediaService.requestUpload(listingId, "image/png", 100L, null).mediaId();
                    } finally {
                        org.springframework.security.core.context.SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertThat(readyGate.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    .as("every racer must be parked at the start gate before the race starts")
                    .isTrue();
            startGate.countDown();
            var positions = new java.util.ArrayList<Integer>();
            for (var id : ids) {
                positions.add(mediaAssetRepository
                        .findById(id.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        .orElseThrow()
                        .getPosition());
            }
            assertThat(positions)
                    .as("the advisory lock serializes position allocation — no duplicates")
                    .doesNotHaveDuplicates()
                    .containsExactlyInAnyOrder(1, 2, 3, 4);
        }
    }

    @Test
    void confirmWithoutStorageVerification_staysPending() {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(false);

        var view = mediaService.requestUpload(listingId, "image/webp", 512L, null);

        try {
            mediaService.confirmUpload(view.mediaId(), null);
        } catch (com.marketplace.shared.api.BadRequestException expected) {
            // the anti-forgery gate: unverified confirm is rejected
        }
        assertThat(mediaAssetRepository.findById(view.mediaId()).orElseThrow().getStatus())
                .isEqualTo(MediaAssetStatus.PENDING_UPLOAD);
    }
}

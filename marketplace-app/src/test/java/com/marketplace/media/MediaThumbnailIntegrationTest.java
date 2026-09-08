package com.marketplace.media;

import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.MediaUploadedEvent;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * L28 (feature-expansion roadmap §5): the thumbnail pipeline against a real
 * database (test profile) with the storage channel mocked at its SDK boundary
 * — the MediaUploadFlowIntegrationTest harness pattern. Every acceptance
 * criterion of the roadmap item is pinned here:
 * <ol>
 *   <li>(1) JPEG confirmed ⇒ a thumbnail object is stored with width ≤ the
 *       bound and both read links work (original + thumb);</li>
 *   <li>(2) a processing failure propagates — the upload stays UPLOADED, the
 *       caller (the listener) lets the publication go FAILED for the
 *       documented resubmission retry (debt D3), and a later retry succeeds;</li>
 *   <li>(3) processing happens outside the request thread by design — the
 *       listener dispatch is the framework's AFTER_COMMIT async path (the
 *       delegate call is proven directly);</li>
 *   <li>(4) a non-processable MIME (gif) ⇒ thumb = original, no failure, no
 *       duplicate object.</li>
 * </ol>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(test.config.ModuleTestConfig.class)
@WithMockUser(roles = "PROVIDER")
class MediaThumbnailIntegrationTest {

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

    private void mockOwner(UUID userId, UUID providerId, UUID listingId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
    }

    /** A real 2000×1000 JPEG — wider than the 640 default bound. */
    private static byte[] wideJpeg() throws Exception {
        BufferedImage image = new BufferedImage(2000, 1000, BufferedImage.TYPE_INT_RGB);
        java.util.Random random = new java.util.Random(42);
        for (int y = 0; y < 1000; y++) {
            for (int x = 0; x < 2000; x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /** request + confirm an upload, returning the persisted asset. */
    private MediaAsset confirmedAsset(String contentType) {
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(true);
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");

        var view = mediaService.requestUpload(listingId, contentType, 2048L, null);
        mediaService.confirmUpload(view.mediaId(), null);
        return mediaAssetRepository.findById(view.mediaId()).orElseThrow();
    }

    @Test
    @DisplayName("(1) confirmed JPEG gets a real scaled thumbnail object and both read links")
    void jpegGetsScaledThumbnail() throws Exception {
        MediaAsset asset = confirmedAsset("image/jpeg");
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());

        mediaService.processThumbnail(asset.getId());

        ArgumentCaptor<byte[]> stored = ArgumentCaptor.forClass(byte[].class);
        verify(storage).putObject(eq(asset.getObjectKey() + "/thumb"), eq("image/jpeg"), stored.capture());
        BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(stored.getValue()));
        assertThat(thumb.getWidth()).as("thumbnail width must honor the bound").isLessThanOrEqualTo(640);
        assertThat(thumb.getHeight()).as("aspect ratio must be preserved").isEqualTo(320);

        MediaAsset persisted = mediaAssetRepository.findById(asset.getId()).orElseThrow();
        assertThat(persisted.getThumbObjectKey()).isEqualTo(asset.getObjectKey() + "/thumb");

        var views = mediaService.listByListing(asset.getListingId());
        assertThat(views).hasSize(1);
        assertThat(views.get(0).downloadUrl()).isEqualTo("https://signed-get");
        assertThat(views.get(0).thumbUrl()).as("the read returns both links").isEqualTo("https://signed-get");
    }

    @Test
    @DisplayName("confirm response carries null thumbUrl — the listener runs AFTER_COMMIT")
    void confirmResponseHasNullThumb() {
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(true);
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");
        UUID userId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        mockOwner(userId, providerId, listingId);

        var view = mediaService.requestUpload(listingId, "image/jpeg", 2048L, null);
        var confirmed = mediaService.confirmUpload(view.mediaId(), null);

        assertThat(confirmed.status()).isEqualTo("UPLOADED");
        assertThat(confirmed.thumbUrl()).as("processing has not run when the confirm response is built").isNull();
    }

    @Test
    @DisplayName("idempotent: a re-run (resubmission) stores nothing and flips no pointer")
    void idempotentOnRerun() throws Exception {
        MediaAsset asset = confirmedAsset("image/jpeg");
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());

        mediaService.processThumbnail(asset.getId());
        mediaService.processThumbnail(asset.getId());

        verify(storage, times(1)).putObject(anyString(), anyString(), any(byte[].class));
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getThumbObjectKey())
                .isEqualTo(asset.getObjectKey() + "/thumb");
    }

    @Test
    @DisplayName("(2) processing failure propagates (publication FAILED, retried) and the upload stays UPLOADED")
    void failurePropagatesThenRetrySucceeds() throws Exception {
        MediaAsset asset = confirmedAsset("image/jpeg");
        when(storage.getObject(asset.getObjectKey())).thenThrow(new RuntimeException("storage read failed"));

        assertThatThrownBy(() -> mediaService.processThumbnail(asset.getId()))
                .isInstanceOf(RuntimeException.class);
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getStatus())
                .as("the upload itself already committed — it stays UPLOADED")
                .isEqualTo(MediaAssetStatus.UPLOADED);
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getThumbObjectKey())
                .as("no thumb pointer is pinned on failure — the retry owns it")
                .isNull();

        // The documented resubmission semantics: the next attempt (storage
        // recovered) completes the pipeline — debt D3's bounded retry.
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());
        mediaService.processThumbnail(asset.getId());
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getThumbObjectKey())
                .isEqualTo(asset.getObjectKey() + "/thumb");
    }

    @Test
    @DisplayName("(4) non-processable MIME (gif) ⇒ thumb = original, no duplicate object, no failure")
    void gifKeepsOriginalAsThumb() {
        MediaAsset asset = confirmedAsset("image/gif");

        mediaService.processThumbnail(asset.getId());

        verify(storage, never()).putObject(anyString(), anyString(), any(byte[].class));
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getThumbObjectKey())
                .as("the thumbnail IS the original by design")
                .isEqualTo(asset.getObjectKey());
    }

    @Test
    @DisplayName("an already-small JPEG keeps the original as thumb (no duplicate object)")
    void smallJpegKeepsOriginalAsThumb() throws Exception {
        MediaAsset asset = confirmedAsset("image/jpeg");
        BufferedImage image = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", out);
        when(storage.getObject(asset.getObjectKey())).thenReturn(out.toByteArray());

        mediaService.processThumbnail(asset.getId());

        verify(storage, never()).putObject(anyString(), anyString(), any(byte[].class));
        assertThat(mediaAssetRepository.findById(asset.getId()).orElseThrow().getThumbObjectKey())
                .isEqualTo(asset.getObjectKey());
    }

    @Test
    @DisplayName("(3) the listener delegates to the pipeline command — the framework owns the async dispatch")
    void listenerDelegatesToThePipeline() {
        MediaService mockService = org.mockito.Mockito.mock(MediaService.class);
        MediaThumbnailListener listener = new MediaThumbnailListener(mockService);
        UUID mediaId = UUID.randomUUID();

        listener.onMediaUploaded(new MediaUploadedEvent(mediaId));

        verify(mockService).processThumbnail(mediaId);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("delete removes the thumbnail object too — only when it is distinct")
    void deleteRemovesDistinctThumbnail() throws Exception {
        MediaAsset asset = confirmedAsset("image/jpeg");
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());
        mediaService.processThumbnail(asset.getId());

        mediaService.delete(asset.getId(), null);

        verify(storage).deleteObject(asset.getObjectKey());
        verify(storage).deleteObject(asset.getObjectKey() + "/thumb");
        assertThat(mediaAssetRepository.findById(asset.getId())).isEmpty();
    }
}

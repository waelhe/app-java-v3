package com.marketplace.media;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ListingPriceProvider;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaServiceTest {

    @Mock
    private MediaAssetRepository repository;
    @Mock
    private ObjectProvider<S3MediaStorage> storageProvider;
    @Mock
    private S3MediaStorage storage;
    @Mock
    private ListingPriceProvider listingPriceProvider;
    @Mock
    private ProviderLookupPort providerLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private Authentication authentication;

    private MediaService service;
    private final UUID userId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    /**
     * D3: a real registry so the thumbnail failure counter's increments are
     * assertable in these unit tests — the same contract the integration
     * test pins against the module slice.
     */
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        service = new MediaService(repository, storageProvider, mediaProperties(),
                listingPriceProvider, providerLookupPort, currentUserProvider, eventPublisher,
                new MediaThumbnailMetrics(meterRegistry));
    }

    /**
     * Builds the media properties stub (bucket, allowed types, size limit)
     * shared by the tests in this class.
     */
    private MediaProperties mediaProperties() {
        return new MediaProperties(
                new MediaProperties.Storage("", "auto", "", "", "", false),
                new MediaProperties.Limits(10_485_760L,
                        Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                        Duration.ofMinutes(15), 640, 25_000_000L));
    }

    /**
     * Stubs the happy-path ownership: the user-owned profile
     * ({@code findByUserId}, A1) matches the asset's provider.
     */
    private void mockOwner() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
    }

    private MediaAsset pendingAsset() {
        return MediaAsset.create(listingId, providerId, "listings/" + listingId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, 1);
    }

    @Test
    void requestUpload_withoutStorageConfigured_answers503() {
        when(storageProvider.getIfAvailable()).thenReturn(null);

        ServiceUnavailableException ex = assertThrows(ServiceUnavailableException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
        assertEquals(503, ex.getStatusCode().value());
    }

    @Test
    void requestUpload_withUnsupportedContentType_rejectsBeforeSigning() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(listingId, "video/mp4", 1024L, authentication));
        verify(storage, never()).presignUpload(any(), any());
    }

    /**
     * An oversize upload is rejected before any presign request is made —
     * validation precedes the storage side effect.
     */
    @Test
    void requestUpload_withOversize_rejectsBeforeSigning() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);

        assertThrows(BadRequestException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 10_485_761L, authentication));
        verify(storage, never()).presignUpload(any(), any());
    }

    /**
     * A provider id owned by another user is denied at upload request
     * time (A1 lookup).
     */
    @Test
    void requestUpload_byNonOwner_isDenied() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", UUID.randomUUID())));

        assertThrows(AccessDeniedException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
        verify(repository, never()).save(any());
    }

    @Test
    void requestUpload_byOwner_returnsPresignedView() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
        mockOwner();
        when(repository.countByListingId(listingId)).thenReturn(0L);
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(storage.presignUpload(any(), eq("image/jpeg"))).thenReturn("https://storage.example/signed-put");

        var view = service.requestUpload(listingId, "IMAGE/JPEG", 2048L, authentication);

        assertEquals("https://storage.example/signed-put", view.uploadUrl());
        assertEquals(Duration.ofMinutes(15), view.urlLifetime());
        // server-generated key: listings/{listingId}/{uuid}.jpg — extension from the normalized type
        assertEquals("jpg", view.objectKey().split("/")[2].split("\\.")[1]);
        assertTrue(view.objectKey().startsWith("listings/" + listingId + "/"));
        verify(storage).presignUpload(view.objectKey(), "image/jpeg");
    }

    @Test
    void requestUpload_forMissingListing_throwsNotFound() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        when(listingPriceProvider.getListingInfo(listingId))
                .thenThrow(new ResourceNotFoundException("Listing", listingId));

        assertThrows(ResourceNotFoundException.class,
                () -> service.requestUpload(listingId, "image/jpeg", 1024L, authentication));
    }

    @Test
    void confirmUpload_whenStorageVerifyFails_staysPending() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(false);

        assertThrows(BadRequestException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
    }

    /**
     * A confirm on an asset whose storage object verified transitions the
     * asset to UPLOADED and persists it.
     */
    @Test
    void confirmUpload_whenVerified_marksUploaded() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(true);
        when(storage.presignDownload(asset.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var view = service.confirmUpload(asset.getId(), authentication);

        assertEquals("UPLOADED", view.status());
        assertEquals("https://storage.example/signed-get", view.downloadUrl());
    }

    /**
     * A provider id owned by another user is denied at upload
     * confirmation (A1 lookup).
     */
    @Test
    void confirmUpload_byNonOwner_isDenied() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", UUID.randomUUID())));

        assertThrows(AccessDeniedException.class,
                () -> service.confirmUpload(asset.getId(), authentication));
        assertEquals(MediaAssetStatus.PENDING_UPLOAD, asset.getStatus());
    }

    @Test
    void confirmUpload_byAdmin_bypassesOwnership() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        lenient().when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
        when(storage.verifyUploaded(asset.getObjectKey(), "image/jpeg", 2048L)).thenReturn(true);
        when(storage.presignDownload(asset.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var view = service.confirmUpload(asset.getId(), authentication);
        assertEquals("UPLOADED", view.status());
    }

    @Test
    void listByListing_onlyReturnsUploadedPresigned() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset uploaded = pendingAsset();
        uploaded.markUploaded();
        when(repository.findByListingIdAndStatusOrderByPositionAsc(listingId, MediaAssetStatus.UPLOADED))
                .thenReturn(java.util.List.of(uploaded));
        when(storage.presignDownload(uploaded.getObjectKey())).thenReturn("https://storage.example/signed-get");

        var views = service.listByListing(listingId);

        assertEquals(1, views.size());
        assertEquals("https://storage.example/signed-get", views.get(0).downloadUrl());
    }

    @Test
    void delete_removesRecordAndBestEffortObject() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();

        service.delete(asset.getId(), authentication);

        verify(repository).delete(asset);
        verify(storage).deleteObject(asset.getObjectKey());
    }

    @Test
    void delete_whenStorageRemovalFails_isStillAcknowledged() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = pendingAsset();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        mockOwner();
        doThrow(new RuntimeException("storage down")).when(storage).deleteObject(asset.getObjectKey());

        service.delete(asset.getId(), authentication);

        verify(repository).delete(asset);
    }

    // ------------------------------------------------------------------
    // D3 closure (roadmap §8, internal-free-work-plan §4): the thumbnail
    // failure counter — every failure is counted by its measured source
    // and ALWAYS propagates (the framework-owned FAILED marking and retry
    // are untouched; counting never swallows).
    // ------------------------------------------------------------------

    /** A real JPEG wider than the 640 default bound (induces a real scale). */
    private static byte[] wideJpeg() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                800, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "jpeg", out);
        return out.toByteArray();
    }

    /**
     * Real PNG-with-alpha bytes: under a jpeg declaration the decode
     * succeeds, the scale succeeds, and the JPEG writer rejects the ARGB
     * raster ("Bogus input colorspace", measured) — the realistic
     * encode-stage failure of a declared-type/actual-bytes mismatch.
     */
    private static byte[] pngWithAlpha() throws Exception {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                800, 400, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private MediaAsset uploadedAsset() {
        MediaAsset asset = pendingAsset();
        asset.markUploaded();
        when(repository.findById(asset.getId())).thenReturn(Optional.of(asset));
        return asset;
    }

    @Test
    void processThumbnail_whenStorageFetchFails_countsFetchAndPropagates() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = uploadedAsset();
        when(storage.getObject(asset.getObjectKey())).thenThrow(new RuntimeException("storage read failed"));

        assertThrows(RuntimeException.class, () -> service.processThumbnail(asset.getId()));

        assertThat(meterRegistry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "storage-fetch").counter().count())
                .as("the fetch-stage failure is counted")
                .isEqualTo(1.0);
        assertThat(asset.getThumbObjectKey())
                .as("no thumb pointer is pinned on failure — the retry owns it")
                .isNull();
    }

    @Test
    void processThumbnail_whenBytesAreUndecodable_countsDecodeAndPropagates() {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = uploadedAsset();
        when(storage.getObject(asset.getObjectKey())).thenReturn("not an image".getBytes());

        assertThrows(IllegalStateException.class, () -> service.processThumbnail(asset.getId()));

        assertThat(meterRegistry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "decode").counter().count())
                .as("the decode-stage failure is counted")
                .isEqualTo(1.0);
        assertThat(asset.getThumbObjectKey()).isNull();
    }

    @Test
    void processThumbnail_whenContentTypeMismatchesBytes_countsEncodeAndPropagates() throws Exception {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = uploadedAsset();
        // declared image/jpeg, actual bytes PNG-with-alpha — decodes fine,
        // dies at the JPEG writer: the measured encode-stage failure.
        when(storage.getObject(asset.getObjectKey())).thenReturn(pngWithAlpha());

        assertThrows(IllegalStateException.class, () -> service.processThumbnail(asset.getId()));

        assertThat(meterRegistry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "encode").counter().count())
                .as("the encode-stage failure is counted")
                .isEqualTo(1.0);
        assertThat(asset.getThumbObjectKey()).isNull();
    }

    @Test
    void processThumbnail_whenThumbnailStoreFails_countsStoreAndPropagates() throws Exception {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = uploadedAsset();
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());
        doThrow(new RuntimeException("storage write failed"))
                .when(storage).putObject(anyString(), anyString(), any(byte[].class));

        assertThrows(RuntimeException.class, () -> service.processThumbnail(asset.getId()));

        assertThat(meterRegistry.get(MediaThumbnailMetrics.FAILURE_COUNTER)
                .tag(MediaThumbnailMetrics.REASON_TAG, "store").counter().count())
                .as("the store-stage failure is counted")
                .isEqualTo(1.0);
        assertThat(asset.getThumbObjectKey()).isNull();
    }

    @Test
    void processThumbnail_onSuccess_countsNoFailure() throws Exception {
        when(storageProvider.getIfAvailable()).thenReturn(storage);
        MediaAsset asset = uploadedAsset();
        when(storage.getObject(asset.getObjectKey())).thenReturn(wideJpeg());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processThumbnail(asset.getId());

        assertThat(meterRegistry.getMeters())
                .as("success registers no failure counter at all")
                .isEmpty();
        assertThat(asset.getThumbObjectKey()).isEqualTo(asset.getObjectKey() + "/thumb");
    }
}

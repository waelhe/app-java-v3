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

    private final UUID userId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID listingId = UUID.randomUUID();

    private void mockOwner() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
        when(providerLookupPort.findById(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
        when(listingPriceProvider.getListingInfo(listingId))
                .thenReturn(new ListingPriceProvider.ListingInfo(providerId, 1000L));
    }

    @Test
    void requestConfirmListDelete_fullLifecycle() {
        mockOwner();
        when(storage.presignUpload(anyString(), anyString()))
                .thenReturn("https://storage.example/signed-put");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(true);
        when(storage.presignDownload(anyString()))
                .thenReturn("https://storage.example/signed-get");

        // 1) request: row persisted PENDING with server-generated key
        var view = mediaService.requestUpload(listingId, "image/jpeg", 2048L, null);
        assertThat(view.uploadUrl()).isEqualTo("https://storage.example/signed-put");
        assertThat(view.objectKey()).startsWith("listings/" + listingId + "/").endsWith(".jpg");
        assertThat(view.urlLifetime()).isEqualTo(Duration.ofMinutes(15));

        var persisted = mediaAssetRepository.findById(view.mediaId()).orElseThrow();
        assertThat(persisted.getStatus()).isEqualTo(MediaAssetStatus.PENDING_UPLOAD);
        assertThat(persisted.getListingId()).isEqualTo(listingId);
        assertThat(persisted.getPosition()).isEqualTo(1);

        // 2) confirm: verified by storage, transitions to UPLOADED
        var confirmed = mediaService.confirmUpload(view.mediaId(), null);
        assertThat(confirmed.status()).isEqualTo("UPLOADED");
        assertThat(confirmed.downloadUrl()).isEqualTo("https://storage.example/signed-get");
        assertThat(mediaAssetRepository.findById(view.mediaId()).orElseThrow().getStatus())
                .isEqualTo(MediaAssetStatus.UPLOADED);

        // 3) read path: only UPLOADED assets, presigned per call
        var listing = mediaService.listByListing(listingId);
        assertThat(listing).hasSize(1);
        assertThat(listing.get(0).id()).isEqualTo(view.mediaId());

        // 4) delete: soft-deleted record, storage object removed best-effort
        mediaService.delete(view.mediaId(), null);
        assertThat(mediaAssetRepository.findById(view.mediaId())).isEmpty();
        assertThat(mediaService.listByListing(listingId)).isEmpty();
    }

    @Test
    void secondAssetGetsNextPosition() {
        mockOwner();
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://u");
        when(storage.presignDownload(anyString())).thenReturn("https://g");
        when(storage.verifyUploaded(anyString(), anyString(), any(Long.class))).thenReturn(true);

        var first = mediaService.requestUpload(listingId, "image/png", 100L, null);
        var second = mediaService.requestUpload(listingId, "image/png", 100L, null);

        assertThat(mediaAssetRepository.findById(first.mediaId()).orElseThrow().getPosition()).isEqualTo(1);
        assertThat(mediaAssetRepository.findById(second.mediaId()).orElseThrow().getPosition()).isEqualTo(2);
    }

    @Test
    void confirmWithoutStorageVerification_staysPending() {
        mockOwner();
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

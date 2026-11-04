package com.marketplace.media;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ProductLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A-17 (compliance plan C.7 — the M1 store root): the media line's third
 * target — the product flow's contracts on the real service, the
 * {@code MediaServiceTest} idioms (storage mocked at the port line, the
 * object-key discipline asserted, the ownership gates pinned through the
 * provider-profile seam the listing flow itself uses).
 */
@ExtendWith(MockitoExtension.class)
class ProductMediaTargetTest {

    @Mock
    private MediaAssetRepository repository;
    @Mock
    private ObjectProvider<S3MediaStorage> storageProvider;
    @Mock
    private S3MediaStorage storage;
    @Mock
    private ProductLookupPort productLookupPort;
    @Mock
    private ProviderLookupPort providerLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private Authentication authentication;

    private MediaService service;
    private final UUID productId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        MediaProperties properties = new MediaProperties(
                new MediaProperties.Storage("", "auto", "", "", "", false),
                new MediaProperties.Limits(10_485_760L, 10, 200,
                        Set.of("image/jpeg", "image/png", "image/webp", "image/gif"),
                        Duration.ofMinutes(15), 640, 25_000_000L));
        service = new MediaService(repository, storageProvider, properties,
                null, null, providerLookupPort, null, productLookupPort, currentUserProvider,
                eventPublisher, new MediaThumbnailMetrics(new SimpleMeterRegistry()));
        lenient().when(storageProvider.getIfAvailable()).thenReturn(storage);
        lenient().when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
    }

    /** The MediaServiceTest mockOwner() pattern — the listing flow's own seam. */
    private void mockOwner() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(ownerId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", ownerId)));
    }

    @Test
    void requestProductUploadRefusesTheTwoHundredAndFirstAllocation() {
        // B19 (CAT-07 — the 2026-11-03 adversarial review, review-pr-809):
        // the per-product media cap at the allocation root. The reorder's
        // absolute-order semantics require the WHOLE displayable set in one
        // GraphQL list, and the bulk-list bound (default 200) refuses a
        // longer one — without the cap, a product above the bound could
        // NEVER be reordered again. The 201st allocation is the classified
        // refusal, checked under the advisory lock (the
        // maxAssetsPerReview posture verbatim).
        mockOwner();
        when(repository.countByProductId(productId)).thenReturn(200L);

        assertThatThrownBy(() ->
                service.requestProductUpload(productId, "image/jpeg", 1024L, authentication))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("A product carries at most 200 media assets")
                .hasMessageContaining("including pending uploads");
        verify(repository).lockProductPositionAllocation(productId.toString());
        verify(repository, never()).save(any(MediaAsset.class));
    }

    @Test
    void requestProductUploadPresignsUnderTheProductsNamespace() {
        mockOwner();
        when(repository.findMaxPositionByProductId(productId)).thenReturn(0);
        when(repository.save(any(MediaAsset.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(storage.presignUpload(anyString(), anyString())).thenReturn("https://signed-put");

        MediaService.MediaUploadView view =
                service.requestProductUpload(productId, "image/jpeg", 1024L, authentication);

        assertThat(view.objectKey()).startsWith("products/" + productId + "/");
        assertThat(view.uploadUrl()).isEqualTo("https://signed-put");
        verify(repository).lockProductPositionAllocation(productId.toString());
        verify(repository).save(any(MediaAsset.class));
    }

    @Test
    void requestProductUploadAnswersTheSeamsOwn404ForAnAbsentProduct() {
        when(productLookupPort.getProductInfo(productId))
                .thenThrow(new ResourceNotFoundException("Product", productId));

        assertThatThrownBy(() ->
                service.requestProductUpload(productId, "image/jpeg", 1024L, authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any(MediaAsset.class));
    }

    @Test
    void requestProductUploadRefusesTheNonOwningProvider() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.requestProductUpload(productId, "image/jpeg", 1024L, authentication))
                .isInstanceOf(AccessDeniedException.class);
        verify(repository, never()).save(any(MediaAsset.class));
    }

    @Test
    void listByProductDegradesHonestlyWhenPhotoLess_NoStorageTouch() {
        mockOwner();
        when(repository.findByProductIdAndStatusOrderByPositionAsc(productId, MediaAssetStatus.UPLOADED))
                .thenReturn(List.of());

        assertThat(service.listByProduct(productId, authentication)).isEmpty();
        verify(storage, never()).presignDownload(anyString());
    }

    @Test
    void listByProductPresignsGetsForUploadedAssets() {
        mockOwner();
        MediaAsset asset = MediaAsset.createForProduct(productId, providerId,
                "products/" + productId + "/a.jpg", "image/jpeg", 1024L, 1);
        asset.markUploaded();
        when(repository.findByProductIdAndStatusOrderByPositionAsc(productId, MediaAssetStatus.UPLOADED))
                .thenReturn(List.of(asset));
        when(storage.presignDownload(anyString())).thenReturn("https://signed-get");

        List<MediaService.MediaAssetView> views = service.listByProduct(productId, authentication);

        assertThat(views).hasSize(1);
        assertThat(views.get(0).productId()).isEqualTo(productId);
        assertThat(views.get(0).downloadUrl()).isEqualTo("https://signed-get");
    }
}

package com.marketplace.media;

import com.marketplace.media.spi.ProductMediaMutationErrorCode;
import com.marketplace.media.spi.ProductMediaView;
import com.marketplace.media.spi.ProductMediaWriteSpi;
import com.marketplace.shared.api.ProductLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * N-slice (the canonical Saleor plan §5-P2 — packet agent-2): the unit
 * proof of the product-media family's owning write use case — the
 * typed-family boundary (a foreign-target row answers the same safe
 * absence as a missing one), the module's own A-17/C.7 ownership law
 * (the admin bypass, the provider-profile link — a stranger's row
 * answers the same safe absence, never an existence disclosure), the
 * deletion's delegation to the module's own {@code MediaService.delete}
 * seam (never a re-implementation), the bulk's honest count, and the
 * reorder's own laws (the safe-absence product gate, the required/
 * duplicated/foreign/partial input refusals, the advisory lock taken
 * BEFORE the reads, the compacted 1..n re-derivation and the
 * pending-intent re-park).
 */
@ExtendWith(MockitoExtension.class)
class ProductMediaWriteServiceTest {

    @Mock
    private MediaAssetRepository mediaAssetRepository;
    @Mock
    private MediaService mediaService;
    @Mock
    private ProductLookupPort productLookupPort;
    @Mock
    private ProviderLookupPort providerLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private ProductMediaWriteSpi service;

    private final UUID userId = UUID.randomUUID();
    private final UUID providerId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProductMediaWriteService(mediaAssetRepository, mediaService,
                productLookupPort, providerLookupPort, currentUserProvider);
    }

    /** The owner stub — the module's own A-17/C.7 law's provider-profile link. */
    private void mockOwner() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
    }

    /** The admin stub — the ownership law's own bypass. */
    private void mockAdmin() {
        lenient().when(currentUserProvider.isAdmin(authentication)).thenReturn(true);
    }

    private MediaAsset uploadedAsset(UUID assetProductId, UUID assetProviderId, int position) {
        MediaAsset asset = MediaAsset.createForProduct(assetProductId, assetProviderId,
                "products/" + assetProductId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, position);
        asset.markUploaded();
        return asset;
    }

    private MediaAsset pendingAsset(UUID assetProductId, UUID assetProviderId, int position) {
        return MediaAsset.createForProduct(assetProductId, assetProviderId,
                "products/" + assetProductId + "/" + UUID.randomUUID() + ".jpg",
                "image/jpeg", 2048L, position);
    }

    // ------------------------------------------------------------------
    // productMediaDelete — the typed boundary, the ownership law, the
    // delegation seam
    // ------------------------------------------------------------------

    @Test
    void aMissingRowAnswersTheSafeAbsence() {
        UUID id = UUID.randomUUID();
        when(mediaAssetRepository.findById(id)).thenReturn(Optional.empty());

        var result = service.deleteProductMedia(id, authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        assertThat(result.errors().get(0).field()).isEqualTo("id");
        assertThat(result.media()).isNull();
        assertThat(result.productId()).isNull();
        verify(mediaService, never()).delete(any(UUID.class), any(Authentication.class));
    }

    @Test
    void aForeignTargetRowAnswersTheSameSafeAbsence() {
        // A LISTING-target row addressed through the product-media root —
        // the typed family's own boundary (never an existence disclosure
        // of another family's row).
        MediaAsset listingRow = MediaAsset.create(productId, providerId,
                "listings/" + productId + "/" + UUID.randomUUID() + ".jpg", "image/jpeg", 2048L, 1);
        when(mediaAssetRepository.findById(listingRow.getId())).thenReturn(Optional.of(listingRow));

        var result = service.deleteProductMedia(listingRow.getId(), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        verify(mediaService, never()).delete(any(UUID.class), any(Authentication.class));
    }

    @Test
    void aStrangersRowAnswersTheSameSafeAbsence() {
        MediaAsset row = uploadedAsset(productId, providerId, 1);
        when(mediaAssetRepository.findById(row.getId())).thenReturn(Optional.of(row));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.empty());

        var result = service.deleteProductMedia(row.getId(), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        verify(mediaService, never()).delete(any(UUID.class), any(Authentication.class));
    }

    @Test
    void theOwnersRowDeletesThroughTheModulesOwnSeam() {
        MediaAsset row = uploadedAsset(productId, providerId, 3);
        when(mediaAssetRepository.findById(row.getId())).thenReturn(Optional.of(row));
        mockOwner();

        var result = service.deleteProductMedia(row.getId(), authentication);

        assertThat(result.errors()).isEmpty();
        assertThat(result.media()).isEqualTo(new ProductMediaView(row.getId(), productId, 3));
        assertThat(result.productId()).isEqualTo(productId);
        verify(mediaService).delete(row.getId(), authentication);
    }

    @Test
    void theAdminBypassDeletesWithoutTheProviderProfileLink() {
        MediaAsset row = uploadedAsset(productId, providerId, 2);
        when(mediaAssetRepository.findById(row.getId())).thenReturn(Optional.of(row));
        mockAdmin();

        var result = service.deleteProductMedia(row.getId(), authentication);

        assertThat(result.errors()).isEmpty();
        assertThat(result.media().position()).isEqualTo(2);
        verify(mediaService).delete(row.getId(), authentication);
    }

    // ------------------------------------------------------------------
    // productMediaBulkDelete — the honest count
    // ------------------------------------------------------------------

    @Test
    void theBulkCountAnswersOnlyWhatTheRegistryItselfDeleted() {
        MediaAsset owned = uploadedAsset(productId, providerId, 1);
        MediaAsset stranger = uploadedAsset(productId, UUID.randomUUID(), 2);
        UUID missing = UUID.randomUUID();
        when(mediaAssetRepository.findById(owned.getId())).thenReturn(Optional.of(owned));
        when(mediaAssetRepository.findById(stranger.getId())).thenReturn(Optional.of(stranger));
        when(mediaAssetRepository.findById(missing)).thenReturn(Optional.empty());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerLookupPort.findByUserId(providerId))
                .thenReturn(Optional.of(new ProviderSummary(providerId, "P", "VERIFIED", userId)));
        when(providerLookupPort.findByUserId(stranger.getProviderId())).thenReturn(Optional.empty());

        var result = service.bulkDeleteProductMedia(
                List.of(owned.getId(), stranger.getId(), missing), authentication);

        assertThat(result.count()).isEqualTo(1);
        assertThat(result.errors()).isEmpty();
        verify(mediaService).delete(owned.getId(), authentication);
        verify(mediaService, never()).delete(stranger.getId(), authentication);
    }

    @Test
    void anEmptyBatchAnswersZeroAndTouchesNothing() {
        var result = service.bulkDeleteProductMedia(List.of(), authentication);

        assertThat(result.count()).isZero();
        assertThat(result.errors()).isEmpty();
        verifyNoInteractions(mediaAssetRepository, mediaService);
    }

    // ------------------------------------------------------------------
    // productMediaReorder — the gate ladder and the rewrite laws
    // ------------------------------------------------------------------

    @Test
    void aMissingProductRequiresTheProduct() {
        var result = service.reorderProductMedia(null, List.of(UUID.randomUUID()), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.REQUIRED);
        assertThat(result.errors().get(0).field()).isEqualTo("productId");
        assertThat(result.media()).isNull();
    }

    @Test
    void anUnknownProductAnswersTheSafeAbsence() {
        when(productLookupPort.getProductInfo(productId))
                .thenThrow(new ResourceNotFoundException("Product not found: " + productId));

        var result = service.reorderProductMedia(productId, List.of(UUID.randomUUID()), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        assertThat(result.errors().get(0).field()).isEqualTo("productId");
    }

    @Test
    void aStrangersProductAnswersTheSameSafeAbsenceAsAnUnknownOne() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(userId);
        when(providerLookupPort.findByUserId(providerId)).thenReturn(Optional.empty());

        var result = service.reorderProductMedia(productId, List.of(UUID.randomUUID()), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        assertThat(result.errors().get(0).field()).isEqualTo("productId");
    }

    @Test
    void anEmptyMediaListIsTheRequiredRefusal() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();

        var result = service.reorderProductMedia(productId, List.of(), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.REQUIRED);
        assertThat(result.errors().get(0).field()).isEqualTo("mediaIds");
    }

    @Test
    void aDuplicatedMediaIdIsTheClassifiedRefusal() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();
        MediaAsset first = uploadedAsset(productId, providerId, 1);

        var result = service.reorderProductMedia(productId, List.of(first.getId(), first.getId()),
                authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code())
                .isEqualTo(ProductMediaMutationErrorCode.DUPLICATED_INPUT_ITEM);
    }

    @Test
    void aForeignMediaIdAnswersTheSafeAbsence() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();
        MediaAsset owned = uploadedAsset(productId, providerId, 1);
        when(mediaAssetRepository.findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.UPLOADED)).thenReturn(List.of(owned));
        UUID foreign = UUID.randomUUID();

        var result = service.reorderProductMedia(productId, List.of(owned.getId(), foreign),
                authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
        assertThat(result.errors().get(0).field()).isEqualTo("mediaIds");
    }

    @Test
    void aPartialListIsTheInvalidRefusal() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();
        MediaAsset first = uploadedAsset(productId, providerId, 1);
        MediaAsset second = uploadedAsset(productId, providerId, 2);
        when(mediaAssetRepository.findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.UPLOADED)).thenReturn(List.of(first, second));

        var result = service.reorderProductMedia(productId, List.of(first.getId()), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.INVALID);
        assertThat(result.errors().get(0).field()).isEqualTo("mediaIds");
    }

    @Test
    void theReorderRewritesOneToNInGivenOrderAndParksPendingIntentsAfter() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();
        MediaAsset first = uploadedAsset(productId, providerId, 1);
        MediaAsset second = uploadedAsset(productId, providerId, 2);
        MediaAsset third = uploadedAsset(productId, providerId, 3);
        MediaAsset pendingIntent = pendingAsset(productId, providerId, 4);
        when(mediaAssetRepository.findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.UPLOADED)).thenReturn(List.of(first, second, third));
        when(mediaAssetRepository.findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.PENDING_UPLOAD)).thenReturn(List.of(pendingIntent));

        // The caller reverses the displayable order — the compacted 1..n
        // re-derivation over the GIVEN order, never the client's absolute
        // positions.
        var result = service.reorderProductMedia(productId,
                List.of(third.getId(), first.getId(), second.getId()), authentication);

        assertThat(result.errors()).isEmpty();
        assertThat(result.productId()).isEqualTo(productId);
        assertThat(result.media()).containsExactly(
                new ProductMediaView(third.getId(), productId, 1),
                new ProductMediaView(first.getId(), productId, 2),
                new ProductMediaView(second.getId(), productId, 3));
        assertThat(third.getPosition()).isEqualTo(1);
        assertThat(first.getPosition()).isEqualTo(2);
        assertThat(second.getPosition()).isEqualTo(3);
        // The pending intent re-parks after the confirmed block — the
        // per-product positions stay unique.
        assertThat(pendingIntent.getPosition()).isEqualTo(4);
        // The advisory lock serializes the whole read-then-rewrite
        // sequence — taken BEFORE the reads (the upload allocation's own
        // stance).
        InOrder order = inOrder(mediaAssetRepository);
        order.verify(mediaAssetRepository).lockProductPositionAllocation(productId.toString());
        order.verify(mediaAssetRepository).findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.UPLOADED);
    }

    @Test
    void anEmptyDisplayableSetWithANonEmptyListAnswersTheSafeAbsence() {
        when(productLookupPort.getProductInfo(productId))
                .thenReturn(new ProductLookupPort.ProductInfo(productId, providerId));
        mockOwner();
        when(mediaAssetRepository.findByProductIdAndStatusOrderByPositionAsc(
                productId, MediaAssetStatus.UPLOADED)).thenReturn(List.of());

        var result = service.reorderProductMedia(productId, List.of(UUID.randomUUID()), authentication);

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).code()).isEqualTo(ProductMediaMutationErrorCode.NOT_FOUND);
    }
}

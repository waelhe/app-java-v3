package com.marketplace.media;

import com.marketplace.media.spi.ProductMediaBulkResult;
import com.marketplace.media.spi.ProductMediaMutationError;
import com.marketplace.media.spi.ProductMediaMutationErrorCode;
import com.marketplace.media.spi.ProductMediaMutationResult;
import com.marketplace.media.spi.ProductMediaReorderResult;
import com.marketplace.media.spi.ProductMediaView;
import com.marketplace.media.spi.ProductMediaWriteSpi;
import com.marketplace.shared.api.ProductLookupPort;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * N-slice (the canonical Saleor plan §5-P2 — packet agent-2): the media
 * owner's write use case for the product-media family's three
 * storage-owned roots — {@code productMediaDelete},
 * {@code productMediaBulkDelete} and {@code productMediaReorder}.
 *
 * <p><b>The deletions ride the module's own existing seam:</b> every
 * row's removal delegates to {@link MediaService#delete(UUID, Authentication)}
 * — the soft delete plus the after-commit storage-object cleanup, the
 * law this service never duplicates. What this use case adds is the
 * product-media family's own boundary law: the addressed row must be a
 * live PRODUCT-target row ({@link MediaOwnerKind#PRODUCT} — a listing's
 * or a post's media addressed through the product-media root answers
 * the same safe absence as a missing row), and the caller must resolve
 * as the row's owner under the module's own A-17/C.7 ownership law (the
 * {@code verifyOwnership} twin: the admin bypass, or the provider
 * profile behind the row's {@code providerId} linked to the caller) —
 * a stranger's row answers the same safe absence, never an existence
 * disclosure (the R5 posture the whole packet carries).</p>
 *
 * <p><b>The reorder rides the module's own position facts:</b> the
 * {@code media_assets.position} column the upload allocation itself
 * manages (the advisory-lock-serialized max+1 append). The given
 * ordered list must cover exactly the product's live displayable
 * (UPLOADED) rows — the set the module's own owner-gated read itself
 * returns; the positions re-derive 1..n in the given order (the
 * compacted re-derivation, never a client-supplied absolute position)
 * and any pending-upload intents re-park after the confirmed block so
 * the per-product positions stay unique. The whole rewrite runs under
 * the module's own {@code lockProductPositionAllocation} discipline,
 * taken BEFORE the reads (the upload allocation's own serialization
 * stance — no concurrent position writer can interleave).</p>
 *
 * <p>Domain validation outcomes answer as result error entries (the
 * Saleor payload-error ADAPT) — never as thrown exceptions; transport
 * concerns never reach this service. The family's remaining roots stay
 * behind the recorded owner decisions (the create's Upload-scalar
 * input, the alt-based update/translate) — never a partial shell
 * here.</p>
 */
@Service
public class ProductMediaWriteService implements ProductMediaWriteSpi {

    private final MediaAssetRepository mediaAssetRepository;
    private final MediaService mediaService;
    private final ProductLookupPort productLookupPort;
    private final ProviderLookupPort providerLookupPort;
    private final CurrentUserProvider currentUserProvider;

    public ProductMediaWriteService(MediaAssetRepository mediaAssetRepository,
                                    MediaService mediaService,
                                    ProductLookupPort productLookupPort,
                                    ProviderLookupPort providerLookupPort,
                                    CurrentUserProvider currentUserProvider) {
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaService = mediaService;
        this.productLookupPort = productLookupPort;
        this.providerLookupPort = providerLookupPort;
        this.currentUserProvider = currentUserProvider;
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMediaMutationResult deleteProductMedia(UUID mediaId, Authentication authentication) {
        Optional<MediaAsset> addressed = ownedProductMediaRow(mediaId, authentication);
        if (addressed.isEmpty()) {
            return ProductMediaMutationResult.refusal(List.of(new ProductMediaMutationError(
                    "id", ProductMediaMutationErrorCode.NOT_FOUND,
                    "No product media found for the given id")));
        }
        MediaAsset row = addressed.get();
        // The module's own deletion seam — the soft delete plus the
        // after-commit storage-object cleanup (the ownership law this
        // service's own pre-check already resolved identically, so the
        // seam's internal gate passes; the delegation is the no-duplication
        // law, never a re-implementation).
        mediaService.delete(row.getId(), authentication);
        return ProductMediaMutationResult.success(
                new ProductMediaView(row.getId(), row.getProductId(), row.getPosition()),
                row.getProductId());
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMediaBulkResult bulkDeleteProductMedia(List<UUID> mediaIds,
                                                         Authentication authentication) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return new ProductMediaBulkResult(0, List.of());
        }
        int deleted = 0;
        for (UUID mediaId : mediaIds) {
            // Unknown or stranger ids are skipped — the honest affected
            // count answers only what the caller owns and the registry
            // itself deleted (the packet's own bulk-delete law: never a
            // fabricated count, never an existence disclosure for
            // another provider's row).
            Optional<MediaAsset> addressed = ownedProductMediaRow(mediaId, authentication);
            if (addressed.isPresent()) {
                mediaService.delete(addressed.get().getId(), authentication);
                deleted++;
            }
        }
        return new ProductMediaBulkResult(deleted, List.of());
    }

    @Override
    @PreAuthorize("hasRole('PROVIDER')")
    @Transactional
    public ProductMediaReorderResult reorderProductMedia(UUID productId, List<UUID> orderedMediaIds,
                                                          Authentication authentication) {
        List<ProductMediaMutationError> errors = new ArrayList<>();
        if (productId == null) {
            errors.add(new ProductMediaMutationError("productId", ProductMediaMutationErrorCode.REQUIRED,
                    "The reorder requires the owning product"));
            return ProductMediaReorderResult.refusal(errors);
        }
        UUID owningProvider = resolveOwningProvider(productId);
        if (owningProvider == null || !callerOwns(owningProvider, authentication)) {
            // The product's own safe absence: an unknown product and a
            // stranger's product answer the same NOT_FOUND refusal —
            // never an existence disclosure (the R5 posture).
            errors.add(new ProductMediaMutationError("productId", ProductMediaMutationErrorCode.NOT_FOUND,
                    "No product found for the given id"));
            return ProductMediaReorderResult.refusal(errors);
        }
        if (orderedMediaIds == null || orderedMediaIds.isEmpty()) {
            errors.add(new ProductMediaMutationError("mediaIds", ProductMediaMutationErrorCode.REQUIRED,
                    "The reorder requires the ordered media ids"));
            return ProductMediaReorderResult.refusal(errors);
        }
        if (new HashSet<>(orderedMediaIds).size() != orderedMediaIds.size()) {
            errors.add(new ProductMediaMutationError("mediaIds",
                    ProductMediaMutationErrorCode.DUPLICATED_INPUT_ITEM,
                    "The reorder list carries a duplicated media id"));
            return ProductMediaReorderResult.refusal(errors);
        }

        // The module's own serialization discipline, taken BEFORE the
        // reads — the upload allocation's own stance: no concurrent
        // position writer (allocation or reorder) can interleave the
        // read-then-rewrite sequence.
        mediaAssetRepository.lockProductPositionAllocation(productId.toString());
        List<MediaAsset> displayable = mediaAssetRepository
                .findByProductIdAndStatusOrderByPositionAsc(productId, MediaAssetStatus.UPLOADED);
        Map<UUID, MediaAsset> byId = new LinkedHashMap<>();
        for (MediaAsset row : displayable) {
            byId.put(row.getId(), row);
        }
        for (UUID mediaId : orderedMediaIds) {
            if (!byId.containsKey(mediaId)) {
                // A foreign id (another target's, another product's, a
                // pending-upload intent's or a stranger's) answers the
                // same safe absence — the displayable set is the only
                // reorderable fact.
                errors.add(new ProductMediaMutationError("mediaIds",
                        ProductMediaMutationErrorCode.NOT_FOUND,
                        "No product media found for the given id"));
                return ProductMediaReorderResult.refusal(errors);
            }
        }
        if (orderedMediaIds.size() != displayable.size()) {
            // The pinned absolute-order semantics: the given list IS the
            // product's whole displayable order — a partial list answers
            // the classified INVALID refusal, never a silent
            // keep-the-rest (the compacted 1..n re-derivation needs the
            // whole set).
            errors.add(new ProductMediaMutationError("mediaIds", ProductMediaMutationErrorCode.INVALID,
                    "The reorder list must cover every media row of the product"));
            return ProductMediaReorderResult.refusal(errors);
        }

        // The compacted 1..n re-derivation over the given order — never
        // a client-supplied absolute position.
        int position = 1;
        List<ProductMediaView> newOrder = new ArrayList<>(orderedMediaIds.size());
        for (UUID mediaId : orderedMediaIds) {
            MediaAsset row = byId.get(mediaId);
            row.reposition(position);
            mediaAssetRepository.save(row);
            newOrder.add(new ProductMediaView(row.getId(), row.getProductId(), row.getPosition()));
            position++;
        }
        // The pending-upload intents re-park after the confirmed block —
        // the per-product positions stay unique and the display order
        // stays exactly the given order (an intent's own slot is
        // invisible until its confirmation, and the confirmation keeps
        // its allocated position).
        for (MediaAsset pending : mediaAssetRepository
                .findByProductIdAndStatusOrderByPositionAsc(productId, MediaAssetStatus.PENDING_UPLOAD)) {
            pending.reposition(position);
            mediaAssetRepository.save(pending);
            position++;
        }
        return ProductMediaReorderResult.success(productId, newOrder);
    }

    /**
     * The addressed row's own typed-family boundary — a live
     * PRODUCT-target row the caller owns, or empty. A missing row, a
     * soft-deleted row, a foreign-target row (a listing's or a post's
     * media) and a stranger's row all answer empty: the same safe
     * absence (the R5 posture — never an existence disclosure).
     */
    private Optional<MediaAsset> ownedProductMediaRow(UUID mediaId, Authentication authentication) {
        if (mediaId == null) {
            return Optional.empty();
        }
        Optional<MediaAsset> row = mediaAssetRepository.findById(mediaId);
        if (row.isEmpty() || row.get().getOwnerKind() != MediaOwnerKind.PRODUCT) {
            return Optional.empty();
        }
        return callerOwns(row.get().getProviderId(), authentication) ? row : Optional.empty();
    }

    /**
     * The product's owning provider — the {@code ProductLookupPort}
     * seam's own carrier (an unknown or soft-deleted product answers
     * null; the port's own documented absence law).
     */
    private UUID resolveOwningProvider(UUID productId) {
        try {
            return productLookupPort.getProductInfo(productId).providerId();
        } catch (ResourceNotFoundException absence) {
            return null;
        }
    }

    /**
     * The module's own A-17/C.7 ownership law — the
     * {@code MediaService.verifyOwnership} twin: the admin bypass, or
     * the provider profile behind the row's {@code providerId} linked
     * to the caller (the A1 fact — {@code providerId} is a user id, so
     * the resolution runs through {@code findByUserId}).
     */
    private boolean callerOwns(UUID providerId, Authentication authentication) {
        if (currentUserProvider.isAdmin(authentication)) {
            return true;
        }
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        return providerLookupPort.findByUserId(providerId)
                .filter(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .isPresent();
    }
}

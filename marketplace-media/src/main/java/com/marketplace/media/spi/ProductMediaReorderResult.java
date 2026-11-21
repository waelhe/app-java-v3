package com.marketplace.media.spi;

import java.util.List;
import java.util.UUID;

/**
 * N-slice: the {@code productMediaReorder} outcome — the product's
 * displayable media in the NEW order (the compacted 1..n positions the
 * rewrite itself derived) plus the owning product's id (the transport's
 * own product projection carrier).
 *
 * @param productId the owning product's native UUID, null on a refused
 *                  outcome
 * @param media     the new-order projections (the row's own position
 *                  after the rewrite), null on a refused outcome
 * @param errors    the domain validation outcomes (empty IS the success
 *                  verdict)
 */
public record ProductMediaReorderResult(UUID productId, List<ProductMediaView> media,
                                        List<ProductMediaMutationError> errors) {

    /** The success verdict over the new-order projections. */
    public static ProductMediaReorderResult success(UUID productId, List<ProductMediaView> media) {
        return new ProductMediaReorderResult(productId, List.copyOf(media), List.of());
    }

    /** The refusal verdict over the given domain outcomes. */
    public static ProductMediaReorderResult refusal(List<ProductMediaMutationError> errors) {
        return new ProductMediaReorderResult(null, null, List.copyOf(errors));
    }
}

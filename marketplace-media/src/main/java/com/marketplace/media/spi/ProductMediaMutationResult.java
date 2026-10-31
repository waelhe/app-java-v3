package com.marketplace.media.spi;

import java.util.List;
import java.util.UUID;

/**
 * N-slice: the {@code productMediaDelete} outcome — the deleted row's
 * last-known projection (the Saleor delete-payload semantics: the
 * payload answers what the row was, never a re-read of a vanished fact)
 * plus the owning product's id (the transport's own product projection
 * carrier).
 *
 * @param media     the deleted row's last-known projection, null on a
 *                 refused outcome
 * @param productId the owning product's native UUID (the payload's
 *                  product field carrier), null on a refused outcome
 * @param errors    the domain validation outcomes (empty IS the success
 *                  verdict)
 */
public record ProductMediaMutationResult(ProductMediaView media, UUID productId,
                                         List<ProductMediaMutationError> errors) {

    /** The success verdict over the given projections. */
    public static ProductMediaMutationResult success(ProductMediaView media, UUID productId) {
        return new ProductMediaMutationResult(media, productId, List.of());
    }

    /** The refusal verdict over the given domain outcomes. */
    public static ProductMediaMutationResult refusal(List<ProductMediaMutationError> errors) {
        return new ProductMediaMutationResult(null, null, List.copyOf(errors));
    }
}

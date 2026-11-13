package com.marketplace.catalog.spi;

import java.util.List;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor collection
 * mutation roots, packet agent-2): the owning use case's domain error
 * entry — the registered "Mutation payload errors" ADAPT divergence's
 * domain side. The {@code products} field carries the product ids that
 * caused the error (the pinned {@code CollectionError} shape's own
 * member; {@code null} when the error is not product-addressed).
 *
 * @param field    the input field that caused the error (null when the
 *                 error is not field-addressed)
 * @param code     the domain error code
 * @param message  the human-readable message
 * @param products the product ids which caused the error (null when none)
 */
public record CollectionMutationError(String field, CollectionMutationErrorCode code,
                                      String message, List<UUID> products) {

    /** The productless constructor — the field-addressed family's own shape. */
    public CollectionMutationError(String field, CollectionMutationErrorCode code, String message) {
        this(field, code, message, null);
    }
}

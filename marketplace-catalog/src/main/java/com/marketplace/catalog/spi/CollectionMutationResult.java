package com.marketplace.catalog.spi;

import java.util.List;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor collection
 * mutation roots, packet agent-2): the owning use case's result contract
 * — the Saleor payload-error shape's own domain mapping (the registered
 * "Mutation payload errors" ADAPT divergence). An empty error list IS
 * the success verdict; {@code collection()} carries the row's own
 * bounded projection (the created/updated row, or the soft-deleted
 * row's last-known projection for the delete roots — Saleor's own
 * payload semantics).
 */
public record CollectionMutationResult(CollectionView collection, List<CollectionMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success verdict — the row's projection with the empty error list. */
    public static CollectionMutationResult success(CollectionView collection) {
        return new CollectionMutationResult(collection, List.of());
    }

    /** The failure verdict — the domain error entries with no projection. */
    public static CollectionMutationResult failure(List<CollectionMutationError> errors) {
        return new CollectionMutationResult(null, List.copyOf(errors));
    }
}

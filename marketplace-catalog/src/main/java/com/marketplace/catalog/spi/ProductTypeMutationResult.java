package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product-type mutation
 * roots, the agent-2 packet): the owning use case's answer to one
 * product-type mutation — the bounded projection on success, the domain
 * error entries on a validation refusal (the registered "Mutation
 * payload errors" ADAPT divergence: domain outcomes ride the payload's
 * own errors field, never the GraphQL error list).
 *
 * @param productType the bounded product-type projection (null on failure)
 * @param errors      the domain error entries (empty on success)
 */
public record ProductTypeMutationResult(ProductTypeView productType, List<ProductTypeMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static ProductTypeMutationResult success(ProductTypeView productType) {
        return new ProductTypeMutationResult(productType, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static ProductTypeMutationResult failure(List<ProductTypeMutationError> errors) {
        return new ProductTypeMutationResult(null, List.copyOf(errors));
    }
}

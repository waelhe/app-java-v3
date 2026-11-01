package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product mutation
 * roots, the agent-2 packet): the owning use case's answer to one
 * product mutation — the bounded projection on success, the domain
 * error entries on a validation refusal (the registered "Mutation
 * payload errors" ADAPT divergence: domain outcomes ride the payload's
 * own errors field, never the GraphQL error list).
 *
 * @param product the bounded product projection (null on failure)
 * @param errors  the domain error entries (empty on success)
 */
public record ProductMutationResult(ProductView product, List<ProductMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static ProductMutationResult success(ProductView product) {
        return new ProductMutationResult(product, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static ProductMutationResult failure(List<ProductMutationError> errors) {
        return new ProductMutationResult(null, List.copyOf(errors));
    }
}

package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product-variant
 * mutation roots, the agent-2 packet): the owning use case's answer to
 * one variant mutation — the bounded projection on success, the
 * domain error entries on a validation refusal (the registered
 * "Mutation payload errors" ADAPT divergence: domain outcomes ride the
 * payload's own errors field, never the GraphQL error list).
 *
 * @param variant the bounded variant projection (null on failure)
 * @param errors  the domain error entries (empty on success)
 */
public record ProductVariantMutationResult(ProductVariantView variant, List<ProductVariantMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static ProductVariantMutationResult success(ProductVariantView variant) {
        return new ProductVariantMutationResult(variant, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static ProductVariantMutationResult failure(List<ProductVariantMutationError> errors) {
        return new ProductVariantMutationResult(null, List.copyOf(errors));
    }
}

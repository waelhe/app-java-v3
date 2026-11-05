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

    /**
     * The failure factory — the domain error entries, no projection. The
     * 2026-11-05 #809 round (Minor · Functional Correctness): an EMPTY
     * error list would fabricate a "failure" whose success() answers
     * TRUE (a null product with zero errors) — the factory now refuses
     * the inconsistent shape loudly, the same never-a-fabricated-result
     * law the payload builders carry.
     */
    public static ProductMutationResult failure(List<ProductMutationError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "A failure result requires at least one domain error");
        }
        return new ProductMutationResult(null, List.copyOf(errors));
    }
}

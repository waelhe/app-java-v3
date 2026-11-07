package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the category mutation roots): the
 * mutation outcome — the domain mapping of Saleor's payload shape (the
 * registered "Mutation payload errors" ADAPT divergence): the bounded
 * projection plus the domain error entries. An empty error list IS the
 * success verdict.
 */
public record CategoryMutationResult(CategoryView category, List<CategoryMutationError> errors) {

    /** The success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }

    /** The success factory. */
    public static CategoryMutationResult success(CategoryView category) {
        return new CategoryMutationResult(category, List.of());
    }

    /** The failure factory — the domain error entries, no projection. */
    public static CategoryMutationResult failure(List<CategoryMutationError> errors) {
        return new CategoryMutationResult(null, List.copyOf(errors));
    }
}

package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantBulkCreate} and {@code productVariantBulkUpdate}
 * mutation roots, the agent-2 follow-on packet): one per-item outcome
 * of the variant registry's bulk write use cases — the single-item
 * ladder's own (projection, errors) answer at its bulk position.
 *
 * @param variant the item's bounded projection (null when the item
 *                was refused)
 * @param errors  the item's domain error entries (empty when the item
 *                succeeded)
 */
public record ProductVariantBulkOutcome(ProductVariantView variant, List<ProductVariantMutationError> errors) {

    /** The item's success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }
}

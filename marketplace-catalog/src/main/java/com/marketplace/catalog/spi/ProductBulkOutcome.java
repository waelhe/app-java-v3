package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productBulkCreate} mutation root, the agent-2 follow-on
 * packet): one per-item outcome of the product registry's bulk create
 * use case — the single-item ladder's own (projection, errors) answer
 * at its bulk position. The transport boundary renders it as Saleor's
 * per-item {@code results[i]} entry.
 *
 * @param product the item's bounded projection (null when the item
 *                was refused)
 * @param errors  the item's domain error entries (empty when the item
 *                succeeded)
 */
public record ProductBulkOutcome(ProductView product, List<ProductMutationError> errors) {

    /** The item's success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }
}

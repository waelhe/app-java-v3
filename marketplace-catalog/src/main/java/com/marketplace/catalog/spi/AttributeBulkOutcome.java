package com.marketplace.catalog.spi;

import java.util.List;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeBulkCreate} and {@code attributeBulkUpdate} mutation
 * roots, the agent-2 follow-on packet): one per-item outcome of the
 * attribute registry's bulk write use cases — the single-item
 * ladder's own (projection, errors) answer at its bulk position. The
 * transport boundary renders it as Saleor's per-item
 * {@code results[i]} entry (the {@code path} pointer is the item's
 * own index + the domain error's field).
 *
 * @param attribute the item's bounded projection (null when the item
 *                  was refused)
 * @param errors    the item's domain error entries (empty when the
 *                  item succeeded)
 */
public record AttributeBulkOutcome(AttributeView attribute, List<AttributeMutationError> errors) {

    /** The item's success verdict — no domain errors. */
    public boolean success() {
        return errors.isEmpty();
    }
}

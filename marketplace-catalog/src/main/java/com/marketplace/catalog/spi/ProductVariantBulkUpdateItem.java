package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantBulkUpdate} mutation root, the agent-2
 * follow-on packet): one update item of the bulk command — the pinned
 * {@code ProductVariantBulkUpdateInput} shape ({@code id} + the
 * bounded movable field set) decoded to its native parts.
 *
 * @param id     the variant row's native UUID (the typed global ID is
 *               decoded at the transport boundary)
 * @param fields the partial update command (sku/name/price — the
 *               omit-to-preserve semantics)
 */
public record ProductVariantBulkUpdateItem(UUID id, ProductVariantUpdateCommand fields) {
}

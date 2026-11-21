package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productTypeReorderAttributes} and
 * {@code attributeReorderValues} mutation roots, the agent-2
 * follow-on packet): one relative-move operation of the reorder
 * command the GraphQL boundary hands the catalog owner's reorder use
 * cases — the pinned {@code ReorderInput} shape verbatim. The owning
 * use case resolves the relative move against the registry's current
 * order (the compacted 1..n re-derivation — the position columns'
 * own deterministic law), never a client-supplied absolute position.
 *
 * @param itemId    the moved row's native UUID (the typed global ID
 *                  is decoded at the transport boundary — an
 *                  attribute assignment's owning attribute for the
 *                  product-type reorder, an attribute value for the
 *                  value reorder)
 * @param sortOrder the relative move (1 one position forward, -1 one
 *                  position backward, 0 unchanged; null = 0)
 */
public record AttributeReorderMove(UUID itemId, Integer sortOrder) {
}

package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionReorderProducts} mutation root, packet agent-2): one
 * relative-move operation of the reorder command the GraphQL boundary
 * hands the catalog owner's membership reorder use case — the pinned
 * {@code MoveProductInput} shape verbatim. The owning use case resolves
 * the relative move against the membership's current order (the
 * compacted 1..n re-derivation — the position column's own deterministic
 * law), never a client-supplied absolute position.
 *
 * @param productId the moved member product's native UUID (the typed
 *                  global ID is decoded at the transport boundary)
 * @param sortOrder the relative move (1 one position forward, -1 one
 *                  position backward, 0 unchanged; null = 0)
 */
public record CollectionProductMove(UUID productId, Integer sortOrder) {
}

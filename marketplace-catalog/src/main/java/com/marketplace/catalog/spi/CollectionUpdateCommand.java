package com.marketplace.catalog.spi;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionUpdate} mutation root, packet agent-2): the amendment
 * command the GraphQL boundary hands the catalog owner's write use case —
 * the bounded owned field set of the pinned {@code CollectionInput}.
 * {@code null} fields preserve the column (partial update — the Saleor
 * input's omit-to-preserve semantics).
 *
 * @param name      the display name amendment (null preserves)
 * @param slug      the public slug amendment (null preserves)
 * @param published the publication fact amendment (null preserves)
 */
public record CollectionUpdateCommand(String name, String slug, Boolean published) {
}

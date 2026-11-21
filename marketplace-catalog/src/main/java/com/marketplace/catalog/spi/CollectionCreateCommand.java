package com.marketplace.catalog.spi;

import java.util.List;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionCreate} mutation root, packet agent-2): the create
 * command the GraphQL boundary hands the catalog owner's write use case —
 * the bounded owned field set of the pinned {@code CollectionCreateInput}:
 * the display {@code name}, the public {@code slug}, the publication fact
 * and the optional member {@code products} (the input's own
 * initial-membership field, addressed as native product UUIDs after the
 * typed global-ID decode at the transport boundary). The unowned members
 * (description/fullDescription as JSONString, seo, backgroundImage as
 * Upload, backgroundImageAlt, externalReference, metadata,
 * privateMetadata, publicationDate) have neither scalar owners nor owning
 * capabilities on this surface and stay undeclared.
 *
 * @param name        the display name (the rendered primary name's own source)
 * @param slug        the stable public slug
 * @param published   the row's own publication fact (the input's isPublished)
 * @param productIds  the initial membership (native product UUIDs; null or
 *                    empty means no members)
 */
public record CollectionCreateCommand(String name, String slug, Boolean published, List<UUID> productIds) {
}

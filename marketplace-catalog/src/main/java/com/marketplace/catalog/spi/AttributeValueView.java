package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeValueCreate}/{@code attributeValueUpdate}/
 * {@code attributeValueDelete} mutation roots, the agent-2 packet):
 * the bounded attribute-value projection the catalog owner exposes at
 * the GraphQL boundary. Only facts the {@code catalog_attribute_options}
 * row owns travel here: the native {@code id}, the rendered display
 * {@code name} (the bilingual row's primary name — {@code nameEn} when
 * present, {@code nameAr} otherwise), and the stable public
 * {@code slug} (the row's immutable {@code code}, unique within the
 * owning attribute). Unowned Saleor fields ({@code value} — no swatch
 * carrier, {@code translation} — no translation owner) stay undeclared
 * at the boundary until an owning capability exists — never faked.
 *
 * @param id   the native {@code catalog_attribute_options} primary key
 * @param name the rendered primary display name (never null: the
 *             entity's own validation guarantees at least one of
 *             nameEn/nameAr)
 * @param slug the stable public code (unique within the owning
 *             attribute — immutable by the entity's own identity law)
 */
public record AttributeValueView(UUID id, String name, String slug) {
}

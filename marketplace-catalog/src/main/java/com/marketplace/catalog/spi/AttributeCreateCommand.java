package com.marketplace.catalog.spi;

import com.marketplace.catalog.CatalogAttributeType;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate} mutation root, the agent-2 packet): the
 * create command the GraphQL boundary hands the catalog owner's
 * attribute write use case. The bounded field set is the row's own
 * Saleor-owned closure: the rendered primary display {@code name}
 * (the bilingual row's name_en — the registered translation-surface
 * divergence keeps the AR dimension with the owning bilingual model
 * until a translation owner exists), the stable public {@code slug}
 * (the row's immutable {@code code}), and the closed domain value
 * shape (the {@code value_type} column). Unowned Saleor
 * {@code AttributeCreateInput} members ({@code entityType},
 * {@code type}, {@code unit}, the {@code values} list,
 * {@code valueRequired}, {@code isVariantOnly},
 * {@code visibleInStorefront}, the deprecated filter flags,
 * {@code externalReference}, {@code referenceTypes}) have no owning
 * columns on this platform and never reach this seam.
 *
 * @param name      the rendered primary display name (the name_en column)
 * @param slug      the stable public code (the row's own slug mapping)
 * @param valueType the closed domain value shape (the value_type column)
 */
public record AttributeCreateCommand(String name, String slug, CatalogAttributeType valueType) {
}

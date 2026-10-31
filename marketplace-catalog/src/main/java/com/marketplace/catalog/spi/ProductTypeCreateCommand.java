package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productTypeCreate}
 * mutation root, the agent-2 packet): the create command the GraphQL
 * boundary hands the catalog owner's product-type write use case. The
 * bounded field set is the row's own Saleor-owned closure: the rendered
 * primary display {@code name} (the bilingual row's name_en — the
 * registered translation-surface divergence keeps the AR dimension with
 * the owning bilingual model until a translation owner exists) and the
 * stable public {@code slug} (the row's immutable {@code code}).
 * Unowned Saleor {@code ProductTypeInput} members ({@code kind},
 * {@code hasVariants}, the attribute id lists, shipping/digital flags,
 * {@code weight}, {@code taxCode}) have no owning columns on this
 * platform and never reach this seam.
 *
 * @param name the rendered primary display name (the name_en column)
 * @param slug the stable public code (the row's own slug mapping)
 */
public record ProductTypeCreateCommand(String name, String slug) {
}

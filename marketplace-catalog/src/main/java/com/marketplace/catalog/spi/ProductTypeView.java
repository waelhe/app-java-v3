package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productType} and
 * {@code productTypes} read roots): the bounded product-type projection the
 * catalog owner exposes at the GraphQL boundary. Only facts the
 * {@code product_types} row owns travel here: the native {@code id}, the
 * rendered display {@code name} (the bilingual row's primary name —
 * {@code nameEn} when present, {@code nameAr} otherwise — the registered
 * translation-surface divergence keeps the AR dimension with the owning
 * bilingual model until a translation owner exists), and the stable public
 * {@code slug} (the row's immutable {@code code}). Unowned Saleor fields
 * stay undeclared at the boundary until an owning capability exists —
 * never faked.
 *
 * @param id   the native {@code product_types} primary key
 * @param name the rendered primary display name (never null: the entity's
 *             own validation guarantees at least one of nameEn/nameAr)
 * @param slug the stable public code (the Saleor slug mapping — immutable
 *             by the entity's own contract)
 */
public record ProductTypeView(UUID id, String name, String slug) {
}

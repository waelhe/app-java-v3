package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code category} and
 * {@code categories} read roots, packet campaign-b4-a): the bounded
 * category projection the catalog owner exposes at the GraphQL boundary.
 * Only facts the {@code categories} registry row owns travel here: the
 * native {@code id}, the rendered display {@code name} (the bilingual
 * row's primary name — {@code nameEn} when present, {@code nameAr}
 * otherwise; nullable because the registry's own columns carry no
 * at-least-one constraint — the registered nullability divergence), the
 * bilingual second name {@code nameAr} (a platform extension fact the
 * Saleor wire shape does not carry), the stable public {@code slug} (the
 * row's immutable {@code code} — the same code-as-slug mapping the
 * attribute slice recorded), and the registry's own display order
 * {@code position} (a platform extension fact). Unowned Saleor fields
 * (the metadata family, SEO fields, rich-text descriptions, the
 * parent/children/ancestors/level tree over a flat registry, the
 * products connection, translations, externalReference) stay undeclared
 * at the boundary until an owning capability exists — never faked.
 *
 * @param id       the native {@code categories} primary key
 * @param name     the rendered primary display name (nameEn else nameAr;
 *                 null only for a registry row carrying neither bilingual
 *                 name — the registered nullability divergence)
 * @param nameAr   the bilingual second name (the registry's own Arabic
 *                 column — a platform extension field, null when unset)
 * @param slug     the stable public code (the Saleor slug mapping —
 *                 immutable by the entity's own contract)
 * @param position the registry's own display order (ascending — a
 *                 platform extension fact, never null by the entity's
 *                 own contract)
 */
public record CategoryView(UUID id, String name, String nameAr, String slug, Integer position) {
}

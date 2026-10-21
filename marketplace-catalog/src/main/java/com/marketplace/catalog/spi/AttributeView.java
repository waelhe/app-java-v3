package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code attribute} and
 * {@code attributes} read roots, the declared continuation of the
 * campaign-b2-a2 product-type slice): the bounded attribute projection the
 * catalog owner exposes at the GraphQL boundary. Only facts the
 * {@code catalog_attribute_definitions} row owns travel here: the native
 * {@code id}, the rendered display {@code name} (the bilingual row's
 * primary name — {@code nameEn} when present, {@code nameAr} otherwise —
 * the registered translation-surface divergence keeps the AR dimension
 * with the owning bilingual model until a translation owner exists), the
 * stable public {@code slug} (the row's immutable {@code code}), and the
 * closed domain value shape {@code valueType}. The Saleor-vocabulary
 * renderings ({@code inputType}, {@code withChoices}) are transport
 * mappings the GraphQL boundary owns — never faked here. Unowned Saleor
 * fields stay undeclared at the boundary until an owning capability
 * exists — never faked.
 *
 * @param id        the native {@code catalog_attribute_definitions} primary key
 * @param name      the rendered primary display name (never null: the
 *                  entity's own validation guarantees at least one of
 *                  nameEn/nameAr)
 * @param slug      the stable public code (the Saleor slug mapping —
 *                  immutable by the entity's own contract)
 * @param valueType the closed domain value shape (never null: the
 *                  entity's own validation)
 */
public record AttributeView(UUID id, String name, String slug,
                            CatalogAttributeType valueType) {
}

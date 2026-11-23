package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code product} and
 * {@code products} read roots, packet campaign-b4-c): the bounded product
 * projection the catalog owner exposes at the GraphQL boundary. Only
 * facts the {@code products} row owns travel here: the native
 * {@code id}, the display {@code name} (the entity's own title), the
 * optional {@code productTypeId} (the reusable goods schema reference —
 * rendered at the boundary as the typed global ID of the existing
 * ProductType root's type), and the display lifecycle {@code status}
 * (the entity's own ACTIVE/SUSPENDED/ARCHIVED/DRAFT state — a platform
 * extension fact the Saleor wire shape does not carry). Unowned Saleor
 * fields (the metadata family, SEO fields, rich-text descriptions, the
 * slug, externalReference, pricing/variants/media/attributes
 * connections, tax fields, weight/sku/rating, translations, updatedAt)
 * stay undeclared at the boundary until an owning capability exists —
 * never faked.
 *
 * @param id            the native {@code products} primary key
 * @param name          the product's display title (never null by the
 *                      entity's own contract)
 * @param productTypeId the optional reusable goods schema reference
 *                      (null keeps pre-C1 and zero-variant products
 *                      compatible)
 * @param status        the display lifecycle state name (never null)
 */
public record ProductView(UUID id, String name, UUID productTypeId, String status) {
}

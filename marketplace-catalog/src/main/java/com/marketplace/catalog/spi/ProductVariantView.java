package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productVariant}
 * and {@code productVariants} read roots, packet campaign-b5-a): the
 * bounded product-variant projection the catalog owner exposes at the
 * GraphQL boundary. Only facts the {@code product_variants} row owns
 * travel here plus the parent product's own bounded projection (the
 * wire's non-null {@code product} field renders the SAME bounded
 * {@link ProductView} the product roots serve — one projection, one
 * vocabulary, never a second product shape): the native {@code id},
 * the optional display {@code name} (the variant's own nullable
 * column — the honest null, never a fabricated placeholder), the
 * seller-namespaced {@code sku} (non-null on this platform), the
 * availability lifecycle {@code status} (the variant's own
 * ACTIVE/SUSPENDED/ARCHIVED state — a platform extension fact the
 * Saleor wire shape does not carry), and the owning {@code product}
 * projection. Unowned Saleor fields (the metadata family, the
 * channel-scoped pricing/channelListings family, weight, the
 * attribute selections, quantityOrdered/revenue reporting, media,
 * translations, updatedAt, trackInventory, quantityLimitPerCustomer,
 * preorder facts) stay undeclared at the boundary until an owning
 * capability exists — never faked.
 *
 * @param id      the native {@code product_variants} primary key
 * @param name    the variant's optional display name (null keeps legacy
 *                free-form variants honest)
 * @param sku     the seller's own SKU (non-null by the row's contract)
 * @param status  the availability lifecycle state name (never null)
 * @param product the owning product's bounded projection (never null)
 */
public record ProductVariantView(UUID id, String name, String sku, String status,
                                 ProductView product) {
}

package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariants} read root, packet campaign-b5-a): one
 * bounded page of the store's product-variant population — the
 * keyset scroll window plus the population total the Saleor
 * {@code totalCount} field expects (the {@code ProductPage}/
 * {@code UserAccountPage} precedent).
 *
 * @param window the scroll window (per-element keyset positions
 *               included — framework-derived for the ADMIN population,
 *               hand-built from the native keyset rows for the public
 *               parent-gated population, ONE cursor semantics either
 *               way)
 * @param total  the scrolled population's own count (the honest
 *               totalCount — the caller-scope population, never
 *               another scope's)
 */
public record ProductVariantPage(Window<ProductVariantView> window, long total) {
}

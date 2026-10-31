package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code products}
 * read root, packet campaign-b4-c): one bounded page of the store's
 * product population — the keyset scroll window plus the population
 * total the Saleor {@code totalCount} field expects (the
 * {@code UserAccountPage}/{@code CategoryPage} precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the scrolled population's own count (the honest
 *               totalCount — the caller-scope population, never another
 *               scope's)
 */
public record ProductPage(Window<ProductView> window, long total) {
}

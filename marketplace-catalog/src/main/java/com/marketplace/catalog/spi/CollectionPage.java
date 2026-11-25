package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor {@code collections}
 * read root, packet agent-2): one bounded page of the collection
 * registry — the keyset scroll window plus the collection total the
 * Saleor {@code totalCount} field expects (the {@code CategoryPage}
 * precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the visible registry's count (the honest totalCount)
 */
public record CollectionPage(Window<CollectionView> window, long total) {
}

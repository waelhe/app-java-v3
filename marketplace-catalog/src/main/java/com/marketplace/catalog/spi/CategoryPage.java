package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categories}
 * read root, packet campaign-b4-a): one bounded page of the category
 * registry — the keyset scroll window plus the collection total the
 * Saleor {@code totalCount} field expects (the {@code UserAccountPage}/
 * {@code PermissionGroupPage}/{@code AttributePage} precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the whole registry's count (the honest totalCount)
 */
public record CategoryPage(Window<CategoryView> window, long total) {
}

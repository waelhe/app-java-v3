package com.marketplace.orders.spi;

import org.springframework.data.domain.Window;

/**
 * P4 (the canonical Saleor plan §5-P4 — the Saleor {@code orders} read
 * root, packet campaign-b5-b): one bounded page of the store's order
 * population — the keyset scroll window plus the population total the
 * Saleor {@code totalCount} field expects (the
 * {@code UserAccountPage}/{@code ProductPage} precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the scrolled population's own count (the honest
 *               totalCount — the whole live order population, the same
 *               population the ADMIN-only scroll reads)
 */
public record OrderPage(Window<OrderView> window, long total) {
}

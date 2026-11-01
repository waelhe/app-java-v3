package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * P2 (the canonical Saleor plan §5-P2): one bounded page of the
 * product-type reference collection — the keyset scroll window plus the
 * collection total the Saleor {@code totalCount} field expects (the
 * {@code UserAccountPage}/{@code PermissionGroupPage} precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the whole collection's count (the honest totalCount)
 */
public record ProductTypePage(Window<ProductTypeView> window, long total) {
}

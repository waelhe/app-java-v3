package com.marketplace.catalog.spi;

import org.springframework.data.domain.Window;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code Collection.products} field, packet agent-2): one bounded page
 * of a collection's member products — the keyset scroll window over the
 * membership's own (position, id) ordering key plus the live membership
 * total the Saleor {@code totalCount} field expects.
 *
 * @param window the scroll window of member-product projections
 * @param total  the live membership's count (the honest totalCount)
 */
public record CollectionProductsPage(Window<ProductView> window, long total) {
}

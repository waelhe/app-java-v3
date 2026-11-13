package com.marketplace.pricing.spi;

import org.springframework.data.domain.Window;

/**
 * P3 (the canonical Saleor plan §5-P3): one bounded page of the
 * platform's voucher policy collection — the keyset scroll window plus
 * the collection total the Saleor {@code totalCount} field expects (the
 * {@code PromotionPage}/{@code ProductTypePage} precedent).
 *
 * @param window the scroll window (per-element keyset positions included)
 * @param total  the whole collection's count (the honest totalCount)
 */
public record VoucherPage(Window<VoucherView> window, long total) {
}

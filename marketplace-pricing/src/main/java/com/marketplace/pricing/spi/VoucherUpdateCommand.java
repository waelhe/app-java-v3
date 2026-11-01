package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the Saleor {@code voucherUpdate}
 * mutation root): the amendment command. A {@code null} field preserves
 * the column — the Saleor input's omit-to-preserve semantics. The
 * temporal fields are ISO-8601 strings (the surface's no-new-scalar
 * decision).
 *
 * <p>Amendments are constrained by the row's own integrity laws: the
 * code may change only while no redemption exists (the entity's own
 * code-stability discipline — an already-redeemed voucher's code is a
 * published contract); the span check re-runs over the MERGED
 * start/end pair.</p>
 */
public record VoucherUpdateCommand(String name, String code, String discountValueType,
                                   Long discountValue, String currency, Long minSpend,
                                   String startDate, String endDate,
                                   Long usageLimit, Boolean applyOncePerCustomer) {

    /** True when no field at all was sent — the honest no-op guard. */
    public boolean isEmpty() {
        return name == null && code == null && discountValueType == null && discountValue == null
                && currency == null && minSpend == null && startDate == null && endDate == null
                && usageLimit == null && applyOncePerCustomer == null;
    }
}

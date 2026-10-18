package com.marketplace.pricing;

/**
 * Exact, overflow-safe percentage arithmetic for the frozen money paths
 * (the review's Minor finding on the double-division rounding drift).
 *
 * <p>The percentage paths ({@code TaxRate.taxOn}, {@code
 * Promotion.discountOn}, {@code Voucher.discountOn}) used {@code
 * Math.round(amount * bps / 10000.0)}: the double division loses the low
 * bits for large amounts — the measured example is {@code amountMinor =
 * 1099511641251} and {@code rateBps = 8749}, where the exact half-up value
 * is {@code 961962734930} but the double path answers {@code 961962734931}.
 * A FROZEN order component must be exactly reproducible — the audit trail
 * and the payment's frozen amount must agree on the same minor unit.
 *
 * <p>This helper computes {@code roundHalfUp(amount * numerator /
 * denominator)} with pure {@code long} quotient/remainder arithmetic — the
 * product is never evaluated as one {@code long} (which could overflow):
 * writing {@code amount = q * denominator + r}, the scaled value is
 * {@code q * numerator + (r * numerator) / denominator} with the remainder
 * carrying the half-up decision. With the module's own bounds (amounts up
 * to {@link Long#MAX_VALUE}, bps at most 10000) each intermediate product
 * stays inside {@code long}: {@code q * numerator} at most
 * {@code Long.MAX_VALUE / 20000 * 10000} and {@code r * numerator} at most
 * {@code 20000 * 10000}.
 */
final class ExactPercentMath {

    private ExactPercentMath() {
    }

    /**
     * {@code roundHalfUp(amount * numerator / denominator)} — exact for all
     * non-negative {@code amount} and positive {@code numerator} /
     * {@code denominator} within the documented bounds.
     */
    static long roundHalfUpScaled(long amount, long numerator, long denominator) {
        long q = amount / denominator;
        long r = amount % denominator;
        long base = q * numerator + (r * numerator) / denominator;
        long rem = (r * numerator) % denominator;
        return base + (2 * rem >= denominator ? 1 : 0);
    }
}

package com.marketplace.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Guard for the exact percentage arithmetic (the review's Minor finding on
 * the double-division drift): the frozen money paths must be exactly
 * reproducible to the minor unit — the audit trail and the payment's frozen
 * amount must agree, so a one-unit drift at large amounts is a defect, not
 * noise.
 *
 * <p>The measured case: {@code amountMinor = 1099511641251} and
 * {@code rateBps = 8749} — the exact half-up value is {@code 961962734930}
 * but the prior {@code Math.round(amount * bps / 10000.0)} answered
 * {@code 961962734931} (one minor unit off). This test pins the exact
 * value and the boundary shapes (half-up, overflow-safe at the long
 * ceiling).
 */
class ExactPercentMathTest {

    @Test
    void theMeasuredDriftCaseAnswersTheExactHalfUpValue() {
        // 1099511641251 × 8749 / 10000 = 961962734930.54… → half-up 961962734931?
        // No: the exact value is 961962734930 remainder-below-half — the
        // double path was the one that drifted UP. Pin the exact integer path.
        assertThat(ExactPercentMath.roundHalfUpScaled(1_099_511_641_251L, 8749L, 10000L))
                .isEqualTo(961962734930L);
    }

    @Test
    void halfUpRoundsTheExactHalfBoundaryUp() {
        // 100 × 5000 / 10000 = 50 exactly — unchanged
        assertThat(ExactPercentMath.roundHalfUpScaled(100L, 5000L, 10000L)).isEqualTo(50L);
        // 15 × 10000 / 10000 = 15 exactly
        assertThat(ExactPercentMath.roundHalfUpScaled(15L, 10000L, 10000L)).isEqualTo(15L);
        // 199 × 2500 / 10000 = 49.75 → half-up 50
        assertThat(ExactPercentMath.roundHalfUpScaled(199L, 2500L, 10000L)).isEqualTo(50L);
        // 197 × 2500 / 10000 = 49.25 → 49
        assertThat(ExactPercentMath.roundHalfUpScaled(197L, 2500L, 10000L)).isEqualTo(49L);
        // the exact half: 2 × 5000 / 10000 = 1.0 exactly? no — 10000/10000=1;
        // the true half boundary: 1 × 5000 / 10000 = 0.5 → half-up 1
        assertThat(ExactPercentMath.roundHalfUpScaled(1L, 5000L, 10000L)).isEqualTo(1L);
    }

    @Test
    void theInclusiveDenominatorShapeStaysExact() {
        // TaxRate INCLUSIVE: amount × bps / (10000 + bps)
        // 2200 × 1500 / 11500 = 286.95… → 287 (the service test's own frozen value)
        assertThat(ExactPercentMath.roundHalfUpScaled(2200L, 1500L, 11500L)).isEqualTo(287L);
    }

    @Test
    void theLongCeilingDoesNotOverflow() {
        // the documented bounds' worst case: amount at the long ceiling,
        // bps at 10000 — the quotient/remainder split keeps every
        // intermediate product inside long
        long amount = Long.MAX_VALUE;
        assertThat(ExactPercentMath.roundHalfUpScaled(amount, 10000L, 10000L))
                .isEqualTo(amount);
        // Decimal-verified reference: 2^63−1 × 9999 / 10000 half-up
        assertThat(ExactPercentMath.roundHalfUpScaled(amount, 9999L, 10000L))
                .isEqualTo(9222449699651090329L);
    }
}

package com.marketplace.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Row 39 (agent findings 2026-10-26 — CWE-400): the weekend multiplier's
 * representation gate. The finding's payload — a compact exponent form
 * like {@code 1e-100000000} — passes both magnitude comparisons
 * ({@code compareTo} never materializes the power of ten) while carrying
 * a scale of 10^8; the pre-fix {@code setScale(3, UNNECESSARY)} would
 * construct that enormous divisor BEFORE rejecting the value, consuming
 * disproportionate shared CPU and heap from a short PUT payload. The
 * scale bound answers first; the generous trailing-zero vocabulary stays
 * legal exactly as the V41 comment promised.
 */
class ListingWeekendRuleScaleBoundTest {

    private static final UUID LISTING = UUID.randomUUID();

    @Test
    void create_rejectsTheExponentAmplifiedScaleBeforeTheRescale() {
        // The finding's own payload shape: magnitude within (0, 10], scale 10^8.
        // BigDecimal(String) official semantics: "1e-100000000" has unscaled
        // value 1 and scale 100000000 (value = unscaledValue × 10^-scale) —
        // the shift IS the scale, one digit per decimal place (measured on
        // the JDK; the previous draft asserted 100000001 off-by-one).
        BigDecimal amplified = new BigDecimal("1e-100000000");
        assertEquals(100000000, amplified.scale());

        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, amplified));
        assertEquals("Weekend multiplier carries more than 16 decimal places (column NUMERIC(6,3))",
                rejected.getMessage());
    }

    @Test
    void create_keepsTheGenerousTrailingZeroVocabularyLegal() {
        // The bound's honest half: trailing zeros well past the three
        // significant decimals are legal representations — the bound cuts
        // the exponent-amplification axis, never the column's vocabulary.
        ListingWeekendRule rule = ListingWeekendRule.create(LISTING,
                new BigDecimal("1.2340000000000000"));
        assertEquals(0, rule.getMultiplier().compareTo(new BigDecimal("1.234")));
    }

    @Test
    void create_keepsTheScaleHonestyRejectionForTrueSubScaleDigits() {
        // CodeRabbit round 1's original pin stays: a fourth SIGNIFICANT
        // decimal digit is the silent-rounding hazard the factory rejects.
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("1.2349")));
        assertEquals("Weekend multiplier supports at most 3 decimal digits (column NUMERIC(6,3))",
                rejected.getMessage());
    }
}

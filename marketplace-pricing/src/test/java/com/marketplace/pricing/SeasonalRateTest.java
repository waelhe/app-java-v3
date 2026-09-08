package com.marketplace.pricing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L26 (feature-expansion roadmap §5): the seasonal-range entity's interval
 * algebra — the exact facts the roadmap's criterion 2 pins: a REAL overlap
 * (even a single shared day) is a conflict, while two ADJACENT ranges
 * sharing a boundary ({@code first.toDate == second.fromDate}) are legal
 * (open intervals); {@code covers} is half-open ({@code toDate} never
 * covered); the factory rejects non-positive spans and negative prices
 * with {@code IllegalArgumentException} (the 400 taxonomy at the service
 * seam).
 */
class SeasonalRateTest {

    private static final UUID LISTING = UUID.randomUUID();

    @Test
    void covers_isHalfOpen_theEndDayIsNeverCovered() {
        SeasonalRate rate = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L);

        assertTrue(rate.covers(LocalDate.parse("2026-01-15")), "the from day is covered");
        assertFalse(rate.covers(LocalDate.parse("2026-01-16")), "the to day is the EXCLUSIVE end");
        assertFalse(rate.covers(LocalDate.parse("2026-01-14")));
    }

    @Test
    void overlaps_singleSharedDay_isAnOverlap() {
        SeasonalRate first = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 20_000L);
        // [Jan 14, Jan 20) shares Jan 14 with [Jan 10, Jan 15) — one day is enough.
        SeasonalRate second = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-14"), LocalDate.parse("2026-01-20"), 25_000L);

        assertTrue(first.overlaps(second));
        assertTrue(second.overlaps(first), "overlap is symmetric");
    }

    @Test
    void overlaps_sharedBoundary_isNotAnOverlap_openIntervals() {
        // first.toDate == second.fromDate — the roadmap's legal adjacency.
        SeasonalRate first = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-15"), 20_000L);
        SeasonalRate second = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-20"), 25_000L);

        assertFalse(first.overlaps(second));
        assertFalse(second.overlaps(first));
    }

    @Test
    void create_rejectsNonPositiveSpanAndNegativePrice() {
        LocalDate from = LocalDate.parse("2026-01-15");

        assertThrows(IllegalArgumentException.class,
                () -> SeasonalRate.create(LISTING, from, from, 1_000L), "zero-length span");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalRate.create(LISTING, from, from.minusDays(1), 1_000L), "reversed span");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalRate.create(LISTING, null, from, 1_000L), "null bound");
        assertThrows(IllegalArgumentException.class,
                () -> SeasonalRate.create(LISTING, from, from.plusDays(1), -1L), "negative price");
    }

    @Test
    void change_reappliesTheSameInvariants() {
        SeasonalRate rate = SeasonalRate.create(LISTING,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L);

        assertThrows(IllegalArgumentException.class,
                () -> rate.change(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-15"), 20_000L));
        assertThrows(IllegalArgumentException.class,
                () -> rate.change(LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), -1L));

        rate.change(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-05"), 30_000L);
        assertEquals(30_000L, rate.getPriceCents());
        assertEquals(LocalDate.parse("2026-02-05"), rate.getToDate());
    }

    @Test
    void weekendRuleFactory_rejectsNullAndOutOfRangeMultiplier() {
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, null));
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("0")));
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("-1")));
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("10.001")));
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(null, new BigDecimal("1.2")));

        ListingWeekendRule rule = ListingWeekendRule.create(LISTING, new BigDecimal("1.2"));
        assertEquals(new BigDecimal("1.2"), rule.getMultiplier());
        assertThrows(IllegalArgumentException.class,
                () -> rule.changeMultiplier(new BigDecimal("0")));
    }

    /**
     * Scale honesty (CodeRabbit round 1): NUMERIC(6,3) would SILENTLY round
     * 1.2349 to 1.235, so a reload prices differently from the submitted
     * rule — the factory rejects values not exactly representable at scale
     * 3, while trailing zeros (1.2340) and scale-3 values stay legal.
     */
    @Test
    void weekendRuleFactory_rejectsScaleBeyondThreeDigits() {
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("1.2349")),
                "would be silently rounded by NUMERIC(6,3)");
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("0.0009")));
        assertThrows(IllegalArgumentException.class,
                () -> ListingWeekendRule.create(LISTING, new BigDecimal("1.23450")));

        // Exactly representable at scale 3 — legal.
        assertEquals(new BigDecimal("1.234"),
                ListingWeekendRule.create(LISTING, new BigDecimal("1.234")).getMultiplier());
        assertTrue(ListingWeekendRule.create(LISTING, new BigDecimal("1.2340")).getMultiplier()
                .compareTo(new BigDecimal("1.234")) == 0);
    }
}

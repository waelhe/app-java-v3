package com.marketplace.pricing;

import org.instancio.Instancio;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Select.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L26 (feature-expansion roadmap §5, Week 3) — the roadmap's two numeric
 * acceptance examples, written exactly as the item states them, plus the
 * most important criterion: a listing with NO calendar rows answers the
 * flat pre-L26 numbers byte-for-byte.
 *
 * <p>Calendar fixture dates: 2026-01-15 is a THURSDAY (2026-01-01 is one;
 * every 7th day after it too) — the roadmap's Thu→Sun stay is
 * {@code 2026-01-15T14:00Z → 2026-01-18T11:00Z}, whose priced nights are
 * exactly Thursday 15, Friday 16 and Saturday 17 (the Sunday 18 checkout
 * is the EXCLUSIVE end, never priced). The rule repositories are mocked
 * to the calendar under test; the tax pipeline runs the DEFAULT rule
 * (15% tax, 0 discount) exactly like {@code PricingServiceTest}.
 */
class PricingServiceWindowTest {

    private static final UUID LISTING = UUID.randomUUID();
    private static final long BASE = 10_000L;
    /** Thu 2026-01-15T14:00Z — check-in. */
    private static final Instant CHECK_IN = Instant.parse("2026-01-15T14:00:00Z");
    /** Sun 2026-01-18T11:00Z — check-out (exclusive end). */
    private static final Instant CHECK_OUT = Instant.parse("2026-01-18T11:00:00Z");

    private final PricingRuleRepository ruleRepository = mock(PricingRuleRepository.class);
    private final ListingWeekendRuleRepository weekendRuleRepository = mock(ListingWeekendRuleRepository.class);
    private final SeasonalRateRepository seasonalRateRepository = mock(SeasonalRateRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final PricingService service = new PricingService(
            ruleRepository, weekendRuleRepository, seasonalRateRepository, eventPublisher, null,
            new PricingProperties(PricingProperties.DEFAULT_MAX_STAY_NIGHTS));

    private void givenNoActiveRule() {
        when(ruleRepository.findByCategoryAndActiveTrue(any())).thenReturn(Optional.empty());
        when(ruleRepository.findFirstByActiveTrueOrderByCreatedAtDesc()).thenReturn(Optional.empty());
    }

    private void givenWeekendMultiplier(String multiplier) {
        ListingWeekendRule rule = Instancio.of(ListingWeekendRule.class)
                .set(field(ListingWeekendRule::getListingId), LISTING)
                .set(field(ListingWeekendRule::getMultiplier), new BigDecimal(multiplier))
                .create();
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.of(rule));
    }

    private void givenSeasonalRanges(SeasonalRate... rates) {
        when(seasonalRateRepository.findByListingIdOrderByFromDateAsc(LISTING)).thenReturn(List.of(rates));
    }

    private static SeasonalRate range(String from, String to, long priceCents) {
        return SeasonalRate.create(LISTING, LocalDate.parse(from), LocalDate.parse(to), priceCents);
    }

    /**
     * Acceptance example (أ) — NO seasonal ranges, weekend rule 1.2: the
     * Thu→Sun stay prices Thu and Fri at base and Sat at ×1.2, so the
     * effective total is 10 000 + 10 000 + 12 000 = 32 000, and the quote's
     * existing tax pipeline (default 15%, zero discount) sits on TOP of it:
     * 32 000 × 1.15 = 36 800.
     */
    @Test
    void windowedQuote_weekendOnly_theRoadmapNumericExampleA() {
        givenNoActiveRule();
        givenWeekendMultiplier("1.2");
        givenSeasonalRanges();

        var breakdown = service.calculatePrice(LISTING, BASE, "services", CHECK_IN, CHECK_OUT);

        assertEquals(32_000L, breakdown.basePriceCents());
        assertEquals(0L, breakdown.discountCents());
        assertEquals(32_000L, breakdown.subtotalCents());
        assertEquals(4_800L, breakdown.taxCents());
        assertEquals(36_800L, breakdown.totalCents());
    }

    /**
     * Acceptance example (ب) — one seasonal range [Thu, Fri) priced 20 000:
     * the covering range REPLACES the base for Thursday (the multiplier
     * never stacks on it), Friday falls back to base, Saturday takes the
     * weekend multiplier: 20 000 + 10 000 + 12 000 = 42 000 effective, and
     * 42 000 × 1.15 = 48 300 with the existing tax. The precedence rule and
     * the exclusive range boundary are both pinned numerically.
     */
    @Test
    void windowedQuote_seasonalRangePrecedence_theRoadmapNumericExampleB() {
        givenNoActiveRule();
        givenWeekendMultiplier("1.2");
        givenSeasonalRanges(range("2026-01-15", "2026-01-16", 20_000L));

        var breakdown = service.calculatePrice(LISTING, BASE, "services", CHECK_IN, CHECK_OUT);

        assertEquals(42_000L, breakdown.basePriceCents());
        assertEquals(6_300L, breakdown.taxCents());
        assertEquals(48_300L, breakdown.totalCents());
    }

    /**
     * The roadmap's MOST IMPORTANT criterion — byte-compatibility for a
     * listing with no calendar rows: the effective total IS the flat base
     * price for every window (the pre-L26 booking number), and the
     * windowed quote equals the legacy two-arg quote component for
     * component.
     */
    @Test
    void noCalendarRules_answersTheFlatNumbers_byteForByte() {
        givenNoActiveRule();
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());
        givenSeasonalRanges();

        long effective = service.calculateBookingTotalCents(LISTING, BASE, CHECK_IN, CHECK_OUT);
        assertEquals(BASE, effective);

        var windowed = service.calculatePrice(LISTING, BASE, "services", CHECK_IN, CHECK_OUT);
        var flat = service.calculatePrice(BASE, "services");

        assertEquals(flat.basePriceCents(), windowed.basePriceCents());
        assertEquals(flat.discountCents(), windowed.discountCents());
        assertEquals(flat.subtotalCents(), windowed.subtotalCents());
        assertEquals(flat.taxCents(), windowed.taxCents());
        assertEquals(flat.totalCents(), windowed.totalCents());
    }

    /**
     * The booking-seam port routes through the SAME day-sliced path as the
     * quote (one code path, two surfaces) — example (أ)'s number, tax-free
     * exactly like the flat price it generalizes.
     */
    @Test
    void bookingSeam_answersTheEffectiveSum_weekendOnly() {
        givenWeekendMultiplier("1.2");
        givenSeasonalRanges();

        long total = service.calculateBookingTotalCents(LISTING, BASE, CHECK_IN, CHECK_OUT);

        assertEquals(32_000L, total);
    }

    /**
     * A same-date window (an intra-day stay, e.g. 10:00→14:00) prices its
     * single check-in date — never zero (a free booking would be a defect,
     * and rejecting the window would break pre-L26 intra-day bookings).
     */
    @Test
    void sameDateWindow_pricesTheSingleCheckInDay() {
        givenWeekendMultiplier("1.2");
        givenSeasonalRanges();

        Instant from = Instant.parse("2026-01-17T10:00:00Z");  // a Saturday
        Instant to = Instant.parse("2026-01-17T14:00:00Z");

        assertEquals(12_000L, service.calculateBookingTotalCents(LISTING, BASE, from, to));
    }

    /**
     * A seasonal range ending exactly at the stay's start (adjacency) never
     * covers the window; the range covering Saturday only leaves Thu/Fri at
     * base — the half-open interval facts from the L27 convention.
     */
    @Test
    void rangesAreHalfOpen_endsWithCheckInDayNeverCovers() {
        givenWeekendMultiplier("1.2");
        givenSeasonalRanges(
                range("2026-01-12", "2026-01-15", 30_000L),
                range("2026-01-17", "2026-01-19", 8_000L));

        long total = service.calculateBookingTotalCents(LISTING, BASE, CHECK_IN, CHECK_OUT);

        // Thu 15 and Fri 16 at base (the first range ends there), Sat 17 at 8 000:
        assertEquals(10_000L + 10_000L + 8_000L, total);
    }

    /**
     * A REVERSED window (check-out strictly before check-in) is 400 on BOTH
     * surfaces — the SearchCriteria gate convention, BEFORE any repository
     * read (the no-calendar flat fallback must not mask it — CodeRabbit
     * round 1). Same-date stays stay legal (the intra-day contract).
     */
    @Test
    void reversedWindow_isRejectedBeforeAnyRead_flatModelOrNot() {
        givenNoActiveRule();
        when(weekendRuleRepository.findByListingId(LISTING)).thenReturn(Optional.empty());
        givenSeasonalRanges();

        Instant reversedIn = Instant.parse("2026-01-18T11:00:00Z");
        Instant reversedOut = Instant.parse("2026-01-15T14:00:00Z");

        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> service.calculateBookingTotalCents(LISTING, BASE, reversedIn, reversedOut));
        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> service.calculatePrice(LISTING, BASE, "services", reversedIn, reversedOut));

        // The gate fires before ANY repository read — no calendar query, no
        // seasonal-rates query, and the tax-rule lookup (which happens only
        // after the effective total) never started either (CodeRabbit
        // round 2: the single never() didn't prove the gate precedes them all).
        verify(weekendRuleRepository, never()).findByListingId(any());
        verify(seasonalRateRepository, never()).findByListingIdOrderByFromDateAsc(any());
        verify(ruleRepository, never()).findByCategoryAndActiveTrue(any());
    }

    // ------------------------------------------------------------------
    // Row 11 (agent findings 2026-10-25 — CWE-834): the business bound
    // ------------------------------------------------------------------

    /**
     * Row 11 — the finding's exact shape: a window spanning far beyond any
     * supported stay (the finding's measured exploit carries a
     * +200000-year endpoint) is 400 on BOTH surfaces BEFORE any repository
     * read. The per-date loop is finite by construction now; the reversed
     * window's own never() triple above is the same proof shape.
     */
    @Test
    void oversizedWindow_isRejectedBeforeAnyRead_onBothSurfaces() {
        Instant farEnd = Instant.parse("9999-01-01T10:00:00Z"); // ~7,900 years of priced dates

        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> service.calculateBookingTotalCents(LISTING, BASE, CHECK_IN, farEnd));
        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> service.calculatePrice(LISTING, BASE, "services", CHECK_IN, farEnd));

        verify(weekendRuleRepository, never()).findByListingId(any());
        verify(seasonalRateRepository, never()).findByListingIdOrderByFromDateAsc(any());
        verify(ruleRepository, never()).findByCategoryAndActiveTrue(any());
    }

    /**
     * Row 11 — the boundary is inclusive: a window of EXACTLY the
     * configured nights (365 by default) is priced, not rejected. A
     * covering seasonal range makes the total deterministic:
     * 365 nights x 5 000 cents.
     */
    @Test
    void windowAtTheExactBound_isPricedNotRejected() {
        Instant in = Instant.parse("2026-01-01T00:00:00Z");
        Instant out = Instant.parse("2027-01-01T00:00:00Z"); // 365 nights (2026 is not a leap year)
        givenSeasonalRanges(range("2025-12-01", "2027-03-01", 5_000L));

        long total = service.calculateBookingTotalCents(LISTING, BASE, in, out);

        assertEquals(365L * 5_000L, total);
    }

    /**
     * Row 11 — the bound is a business knob, not a hardcode: a deployment
     * configured for 3-night stays rejects the 4-night window before any
     * read, on the same gate the default bound rides.
     */
    @Test
    void configuredBound_isEnforcedFromConfiguration() {
        PricingService tight = new PricingService(ruleRepository, weekendRuleRepository,
                seasonalRateRepository, eventPublisher, null, new PricingProperties(3));

        Instant fourNightsIn = Instant.parse("2026-01-15T14:00:00Z");
        Instant fourNightsOut = Instant.parse("2026-01-19T11:00:00Z");

        assertThrows(com.marketplace.shared.api.BadRequestException.class,
                () -> tight.calculateBookingTotalCents(LISTING, BASE, fourNightsIn, fourNightsOut));
        verify(weekendRuleRepository, never()).findByListingId(any());
        verify(seasonalRateRepository, never()).findByListingIdOrderByFromDateAsc(any());
    }
}

package com.marketplace.pricing;

import java.util.List;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): the host-facing view of one
 * listing's whole price calendar — the weekend rule (nullable — absent
 * means the flat model) plus the seasonal ranges ordered by start date.
 * The effective price of any stay window is derived from exactly this
 * data through {@code PricingService.calculatePrice(listingId, ...)} (the
 * quote surface) and {@code EffectivePricePort} (the booking seam).
 *
 * <p>S7 (platform-readiness audit §5 — the shape row): the calendar
 * carries the listing's ISO 4217 currency at the top level — the single
 * money context of every {@code priceCents} amount inside (the seasonal
 * rates and the derived quotes share the listing's currency by
 * construction, so it is stated once, not repeated per row).
 */
public record ListingCalendarResponse(
        UUID listingId,
        String currency,
        WeekendRuleResponse weekendRule,
        List<SeasonalRateResponse> seasonalRates
) {
}

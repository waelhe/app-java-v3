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
 */
public record ListingCalendarResponse(
        UUID listingId,
        WeekendRuleResponse weekendRule,
        List<SeasonalRateResponse> seasonalRates
) {
}

package com.marketplace.pricing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): the host-facing view of one
 * listing's weekend rule (the multiplier on the base price for weekend
 * days). Null inside {@link ListingCalendarResponse} when the listing has
 * no weekend rule — the flat-model signal (the roadmap's activation
 * criterion: no calendar rows, no day-sliced pricing).
 */
public record WeekendRuleResponse(
        UUID id,
        UUID listingId,
        BigDecimal multiplier,
        Instant createdAt,
        Instant updatedAt
) {
}

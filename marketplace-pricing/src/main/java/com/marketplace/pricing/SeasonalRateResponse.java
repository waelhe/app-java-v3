package com.marketplace.pricing;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): the host-facing view of one seasonal
 * range of a listing's price calendar — an absolute price over
 * {@code [fromDate, toDate)} with an EXCLUSIVE end (the same interval
 * convention as the stay window; the day {@code toDate} names is never
 * priced by this range).
 */
public record SeasonalRateResponse(
        UUID id,
        UUID listingId,
        LocalDate fromDate,
        LocalDate toDate,
        long priceCents,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.marketplace.provider;

import java.time.Instant;

/**
 * L25 (feature-expansion roadmap §5): the provider's own three aggregate
 * reads for a window — occupancy ratio, net revenue (post-commission ledger
 * movement) and completed bookings. Read-only; no breakdown rows, no
 * precomputed comparisons, no export (explicitly out of scope).
 */
public record ProviderStatsResponse(
        Instant from,
        Instant to,
        double occupancyRate,
        long netRevenueCents,
        long completedBookings
) {
}

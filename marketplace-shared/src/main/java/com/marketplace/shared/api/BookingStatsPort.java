package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): read-only port for the provider-stats
 * completed-booking count. Implemented by {@code BookingStatsAdapter}
 * (marketplace-booking), consumed by the provider module's stats
 * aggregation. Same pattern as {@code ReviewStatsPort} (L21).
 */
public interface BookingStatsPort {

    /**
     * How many of the provider's bookings with status {@code COMPLETED}
     * start inside the window — {@code starts_at} in {@code [from, to)}
     * (the house exclusive-end convention, the same one the slot stats use,
     * so both halves of the report measure the same window).
     */
    long countCompletedForProviderBetween(UUID providerId, Instant from, Instant to);
}

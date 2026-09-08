package com.marketplace.provider;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.BookingStatsPort;
import com.marketplace.shared.api.LedgerStatsPort;
import com.marketplace.shared.api.SlotWindowStats;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): the provider's windowed aggregates —
 * three read-only projections over the cross-module read ports
 * (availability slot stats, ledger net movement, booking completed count).
 * The aggregation is pure arithmetic; every domain rule lives behind its
 * owning module's port.
 *
 * <p>Ownership: the unit's convention
 * {@code @authHelper.ownsProvider} on the service method — the controller
 * resolves the "me" provider independently (the L20 double-check seam).
 *
 * <p>Cache: short-TTL {@code provider-stats} keyed by provider+window
 * (roadmap: "مخبأة قصيرة TTL بمفتاح المزوّد+النافذة"). The key concatenation
 * is injective here by charset: UUIDs and ISO-8601 instants contain no
 * {@code |} (the L27 free-text collision class cannot arise — the key
 * components are server-resolved values, never user text). Freshness is
 * TTL-bounded (5m, the RedisCacheManagerBuilderCustomizer in
 * marketplace-app) by the roadmap's own choice — write invalidation is
 * deliberately not wired: three modules would have to evict a fourth's
 * cache, and a bounded dashboard staleness is the documented trade.
 */
@Service
@Transactional(readOnly = true)
public class ProviderStatsService {

    private final AvailabilityLookupPort availabilityLookupPort;
    private final LedgerStatsPort ledgerStatsPort;
    private final BookingStatsPort bookingStatsPort;

    public ProviderStatsService(AvailabilityLookupPort availabilityLookupPort,
                                LedgerStatsPort ledgerStatsPort,
                                BookingStatsPort bookingStatsPort) {
        this.availabilityLookupPort = availabilityLookupPort;
        this.ledgerStatsPort = ledgerStatsPort;
        this.bookingStatsPort = bookingStatsPort;
    }

    /**
     * The three aggregates for one window. Occupancy is
     * {@code bookedSlots / totalSlots} — 0.0 when the provider has no slot
     * in the window (no NaN, no 400: a legit empty answer).
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    @Cacheable(cacheNames = "provider-stats", key = "#providerId + '|' + #window.from + '|' + #window.to")
    public ProviderStatsResponse getStats(UUID providerId, StatsWindow window) {
        SlotWindowStats slotStats = availabilityLookupPort
                .findProviderSlotStats(providerId, window.from(), window.to());
        long netRevenueCents = ledgerStatsPort
                .findNetCentsForProviderBetween(providerId, window.from(), window.to());
        long completedBookings = bookingStatsPort
                .countCompletedForProviderBetween(providerId, window.from(), window.to());

        double occupancyRate = slotStats.totalSlots() == 0
                ? 0.0
                : (double) slotStats.bookedSlots() / slotStats.totalSlots();

        return new ProviderStatsResponse(
                window.from(), window.to(), occupancyRate, netRevenueCents, completedBookings);
    }
}

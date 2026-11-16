package com.marketplace.provider;

import java.time.Instant;
import java.util.List;

import com.marketplace.shared.api.CurrencyAmount;

/**
 * L25 (feature-expansion roadmap §5): the provider's own three aggregate
 * reads for a window — occupancy ratio, net revenue (post-commission ledger
 * movement) and completed bookings. Read-only; no breakdown rows, no
 * precomputed comparisons, no export (explicitly out of scope).
 *
 * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's
 * currency):</b> the net revenue is PER CURRENCY — one
 * {@link CurrencyAmount} per ISO 4217 code the window touches, the same
 * grouped shape the ledger's balances carry. The pre-fix single
 * {@code netRevenueCents} summed different currencies into one number (the
 * balance defect's own class); a window with no ledger movement answers
 * the empty list.</p>
 *
 * <p><b>Serializable — the provider-stats cache value (R10, live-measured
 * defect family):</b> {@code ProviderStatsService#getStats} caches this
 * record under {@code provider-stats} (short TTL) and the production cache
 * type is Redis (spring-boot-cache 4.1.1 {@code RedisCacheConfiguration} —
 * the {@code RedisValueSerializer} default requires cached types to be
 * {@link Serializable}). A non-Serializable value made every cold-cache PUT
 * throw {@code IllegalStateException("Cannot serialize value of type …
 * without a serializer")}, which the shared {@code GlobalExceptionHandler}
 * maps to 409 CONFLICT-001 — the live verdict the frontend write-battery
 * measured on {@code GET /providers/me/stats} (STATS-409, card BE-05).
 * Same seam, same fix, as {@code GeoLookupPort.GeoNode} this round and the
 * {@code Page<ListingSummary>}/entity/{@code PriceBreakdown} family before
 * it ({@code ColdCacheRedisSerializationIntegrationTest}). The
 * {@code CurrencyAmount} carrier is Serializable for the same reason.</p>
 *
 * <p>Fixed {@code serialVersionUID}: an evolved record degrades to
 * {@code InvalidClassException} on stale entries — the 5-minute TTL makes
 * that failure self-healing.</p>
 */
public record ProviderStatsResponse(
        Instant from,
        Instant to,
        double occupancyRate,
        List<CurrencyAmount> netRevenue,
        long completedBookings
) implements java.io.Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;
}

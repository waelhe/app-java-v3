package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5): the booking-seam port for the
 * effective price — the per-day pricing total a booking carries. The
 * pricing module owns the implementation (PricingService — the roadmap's
 * "PricingService يبقى المالك الحسابي"); the booking module consumes it
 * without depending on pricing internals (the ListingPriceProvider
 * synchronous-port pattern: the caller needs the number before persisting).
 *
 * <p>Contract: {@code [startsAt, endsAt)} — the exclusive-end stay window
 * (the same convention as the L27 search window). A listing with no
 * calendar rows (no weekend rule, no seasonal ranges) answers the FLAT
 * listing base price — byte-compatible with the pre-L26 booking path (the
 * roadmap's most important criterion); otherwise the window is day-sliced
 * with the precedence rule (a covering seasonal range's absolute price
 * replaces the base; the weekend multiplier applies to base days outside
 * the ranges and never stacks on seasonal days).
 */
public interface EffectivePricePort {

    /**
     * The effective total for a stay window — the number the booking stores
     * in {@code price_cents} (tax-free, exactly like the flat listing price
     * it generalizes; the tax pipeline belongs to the quote surface
     * {@code PricingService.calculatePrice}).
     *
     * @param listingId     the listing whose calendar applies
     * @param basePriceCents the listing's flat base price (cents) — the
     *                      caller already resolved it through
     *                      {@link ListingPriceProvider}
     * @param startsAt      stay start (inclusive side of the window)
     * @param endsAt        stay end (exclusive side)
     */
    long calculateBookingTotalCents(UUID listingId, long basePriceCents, Instant startsAt, Instant endsAt);
}

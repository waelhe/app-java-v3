package com.marketplace.provider;

import com.marketplace.shared.api.ListingViewsStatsPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's own
 * listing-view totals — the {@code ProviderStatsService} shape (L25): a
 * read-only projection over a cross-module read port, the aggregation
 * itself living behind the owning module's boundary (catalog owns the
 * daily buckets).
 *
 * <p>Ownership: the unit's convention
 * {@code @authHelper.ownsProvider} on the service method — the controller
 * resolves the "me" provider independently (the L20 double-check seam).
 *
 * <p>Freshness: deliberately NO {@code @Cacheable} — unlike the
 * occupancy/revenue dashboard (a 5-minute-stale summary the roadmap
 * itself chose to cache), the views surface feeds an increment-only
 * table whose whole point is "how many people saw my listing TODAY", and
 * there is no write path on the provider side that could evict a
 * provider-stats-style cache honestly. Always-fresh is one indexed
 * aggregate query per dashboard open — the cheap side of the trade, with
 * zero invalidation wiring (the AFTER_COMMIT relay covers entity
 * changes; {@code listing_views_daily} changes are visitor reads, not
 * provider writes).
 */
@Service
@Transactional(readOnly = true)
public class ProviderListingViewsService {

    private final ListingViewsStatsPort listingViewsStatsPort;

    public ProviderListingViewsService(ListingViewsStatsPort listingViewsStatsPort) {
        this.listingViewsStatsPort = listingViewsStatsPort;
    }

    /**
     * The caller's per-listing view totals for the window — the UTC day
     * arithmetic stays here (one clock read, one seam), the bucket sum
     * behind the port.
     */
    @PreAuthorize("@authHelper.ownsProvider(#providerId, authentication)")
    public ProviderListingViewsResponse getViews(UUID providerId, ListingViewsWindow window) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate sinceInclusive = window.sinceInclusive(today);
        return new ProviderListingViewsResponse(
                window.days(), sinceInclusive,
                listingViewsStatsPort.findViewTotalsForProviderSince(providerId, sinceInclusive));
    }
}

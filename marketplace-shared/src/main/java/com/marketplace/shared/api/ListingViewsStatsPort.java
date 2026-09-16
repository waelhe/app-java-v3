package com.marketplace.shared.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): read-only port for
 * the provider's own listing-view totals. The catalog module owns the
 * daily aggregate table, so the day-bucketed sum lives behind this
 * boundary. Implemented by {@code ListingViewsStatsAdapter}
 * (marketplace-catalog), consumed by the provider module's views surface
 * — the same pattern {@code LedgerStatsPort} (L25) settled for the
 * windowed stats family.
 *
 * <p><b>Id space (the AuthHelper A1 contract):</b> the argument is the
 * provider's USER id — {@code provider_listings.provider_id} carries
 * {@code users.id}, exactly like every cross-module provider_id column.
 * The caller resolves the "me" provider and passes the authenticated
 * user's id.
 */
public interface ListingViewsStatsPort {

    /**
     * The provider's per-listing view totals from {@code sinceInclusive}
     * (a UTC day, inclusive) through today's UTC day (inclusive) — the
     * 7/30/90-day windows end "now", so the newest bucket (today, still
     * accumulating) is part of every window: an analytics surface that
     * hides today's views would answer a question nobody asked.
     *
     * <p>Contract: one row per listing that has at least one view inside
     * the window (a never-viewed listing is absent — the honest empty
     * answer, not a zero-filled roster), ordered by views descending with
     * the listing id ascending as the deterministic tiebreak (the L32
     * stable-order rule). All of the provider's listings qualify
     * regardless of status — a paused or archived listing's past views
     * are history, not a secret.
     *
     * @param providerUserId the provider's user id (the A1 users.id space)
     * @param sinceInclusive the window's first UTC day, inclusive
     * @return the per-listing totals, views DESC / id ASC
     */
    List<ListingViewStats> findViewTotalsForProviderSince(UUID providerUserId, LocalDate sinceInclusive);
}

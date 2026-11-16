package com.marketplace.provider;

import java.time.LocalDate;
import java.util.List;

import com.marketplace.shared.api.ListingViewStats;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's own
 * per-listing view totals for the requested window. The window travels
 * with the numbers so the response is self-describing (the
 * {@code ProviderStatsResponse} convention: the caller never guesses
 * which window produced these numbers). The entries are the shared
 * {@link ListingViewStats} contract — one row per listing that was
 * actually viewed inside the window, views DESC / id ASC.
 *
 * @param days            the window's length in days (7, 30 or 90)
 * @param sinceInclusive  the window's first UTC day (inclusive) — today is
 *                        the last included bucket
 * @param listings        the per-listing view totals (never null; empty
 *                        when nothing was viewed inside the window)
 */
public record ProviderListingViewsResponse(
        int days,
        LocalDate sinceInclusive,
        List<ListingViewStats> listings) {
}

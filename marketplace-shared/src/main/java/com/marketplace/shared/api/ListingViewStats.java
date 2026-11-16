package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): one listing's view
 * total over the requested window, for the provider's own analytics
 * surface. The listing id is the catalog-space {@code provider_listings.id};
 * the title is the current title of that listing (the join the aggregate
 * rides — the provider recognizes the row by title, the id is the stable
 * key); the count is the sum of {@code listing_views_daily.view_count}
 * over the window's UTC day buckets.
 *
 * @param listingId the listing's id (catalog space, {@code provider_listings.id})
 * @param title     the listing's current title (display recognition —
 *                  the id is the stable key)
 * @param views     deduplicated views summed over the window (0 when the
 *                  listing was never viewed inside it)
 */
public record ListingViewStats(UUID listingId, String title, long views) {
}

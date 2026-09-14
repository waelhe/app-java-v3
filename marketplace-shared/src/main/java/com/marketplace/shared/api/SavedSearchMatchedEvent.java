package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): published
 * by the search module's matcher after a newly activated listing matched
 * one or more of a user's alert-enabled saved searches, consumed by the
 * notifications module for the {@code SAVED_SEARCH_MATCH} alert.
 *
 * <p><b>Aggregation by design (the plan's criterion 4 — the self-storm
 * guard):</b> the event carries the LIST of matched saved-search ids for
 * ONE user and ONE listing — a user with a hundred saved searches all
 * matching the same new listing receives exactly one notification, not a
 * hundred. The listener groups its scan results per user before
 * publishing, so the aggregation is structural, not a downstream
 * best-effort.
 *
 * <p><b>Exactly-once composition (the plan's criterion 3-b):</b> the
 * matcher publishes this event only for NEW match rows (the
 * {@code saved_search_matches} partial-unique skip); a listener re-run on
 * the same {@code ListingActivatedEvent} inserts nothing new and
 * publishes nothing — the notification cannot double. A failure to deliver
 * after publication is the registry's retry (criterion 3), which re-fires
 * THIS event alone — the matching itself is never re-executed.
 *
 * <p>The Modulith house pattern ({@code BookingCreatedEvent} precedent):
 * identifiers only, published inside the matcher's own transaction (the
 * {@code @ApplicationModuleListener} REQUIRES_NEW unit) so the registry
 * entry commits atomically with the match rows.
 */
public record SavedSearchMatchedEvent(
        UUID userId,
        UUID listingId,
        List<UUID> savedSearchIds
) {
}

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
 * <p><b>Idempotent matching, at-least-once delivery (the plan's criterion
 * 3-b, split per the CodeRabbit round-1 adoption):</b> the matcher
 * publishes this event only for NEW match rows (the
 * {@code saved_search_matches} partial-unique skip) — a listener re-run on
 * the same {@code ListingActivatedEvent} inserts nothing new and
 * publishes nothing, so the MATCH cannot double and re-running the
 * matching can never re-publish THIS event. Downstream channel delivery
 * is a different contract: it is at-least-once per the publication
 * registry's retry — a re-delivery of this event after a partial
 * channel failure repeats the earlier channels' effects (the in-app row,
 * email, WebSocket), exactly like the three standing L22 event points.
 * Per-channel exactly-once delivery is the declared debt D-E10 (the
 * plan's §8): one unified notifications outbox for the whole module at
 * its measured closure point — not a per-type patch here.
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

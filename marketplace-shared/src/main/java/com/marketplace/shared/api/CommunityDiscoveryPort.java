package com.marketplace.shared.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * The community module's discovery projection source — the eligibility-
 * first queries the discovery rails assemble from (the #536
 * backend-execution contract: eligibility, visibility, validity are
 * applied DETERMINISTICALLY before any ordering or personalization —
 * AC-20-01: the server rejects an ineligible element even when search or
 * AI would nominate it).
 *
 * <p>House pattern (the {@code PostLookupPort} precedent verbatim): the
 * interface lives in shared-api, marketplace-community implements it, the
 * discovery module injects it — no cross-module repository access. Every
 * query returns VISIBLE, non-deleted, correctly-scoped records ONLY: a
 * moderated or soft-deleted record never surfaces here by construction,
 * and the caller's scope filter is applied inside the adapter (the
 * adapter enforces its own domain's gates — the port never trusts the
 * caller to re-filter).</p>
 */
public interface CommunityDiscoveryPort {

    /**
     * Active LOST_FOUND posts in the neighborhood — the rail's contract
     * (JT-20 flow §5): only {@code lost_found_state = ACTIVE}; resolved /
     * recovered reports surface on the topic page through the explicit
     * lifecycle filter, never as active.
     */
    PagedResponse<DiscoveryPostCard> findActiveLostFound(UUID locationId, PagedRequest page);

    /** RECOMMENDATION posts in the neighborhood, newest first. */
    PagedResponse<DiscoveryPostCard> findRecommendations(UUID locationId, PagedRequest page);

    /**
     * Upcoming events in the neighborhood — status-honest: CANCELLED /
     * POSTPONED events never masquerade as upcoming; the state travels on
     * the card so the client renders it truthfully.
     */
    PagedResponse<DiscoveryEventCard> findUpcomingEvents(UUID locationId, PagedRequest page);

    /**
     * The newest VISIBLE posts the given users authored INSIDE the
     * caller's neighborhood (the followed-sources rail's community leg —
     * the reader's explicit follow choice, never an algorithmic
     * substitute, AC-20-05).
     *
     * <p><b>The neighborhood scope is load-bearing (row 24, CWE-862):</b>
     * the follow relationship widens the AUTHOR axis only — never the
     * geographic one. A unilateral follow must never surface post text
     * the normal post-read boundary denies the same caller: the
     * {@code getFeed}/{@code getPost} gates scope every read to the
     * caller's active-membership neighborhood, so the followed-authors
     * query composes the SAME {@code locationId} predicate the feed
     * rides. A followed author's post from another neighborhood is
     * absent here by construction — the adapter enforces the scope, the
     * port never trusts the caller to re-filter (the house rule this
     * interface's own javadoc states).</p>
     *
     * @param locationId the caller's ACTIVE neighborhood — the same
     *                   membership-derived scope every community read
     *                   composes
     * @param authorIds  the followed authors (the caller's stored USER
     *                   follows)
     */
    PagedResponse<DiscoveryPostCard> findRecentByAuthors(UUID locationId, Set<UUID> authorIds, PagedRequest page);

    /** One neighborhood post as the projection speaks it. */
    record DiscoveryPostCard(
            UUID postId,
            UUID authorId,
            String category,
            String lostFoundState,
            String title,
            String body,
            String status,
            UUID locationId,
            Instant updatedAt) {
    }

    /** One neighborhood event as the projection speaks it. */
    record DiscoveryEventCard(
            UUID eventId,
            UUID locationId,
            String title,
            String description,
            String status,
            String locationLabel,
            Instant startsAt,
            Instant endsAt,
            Instant updatedAt) {
    }
}

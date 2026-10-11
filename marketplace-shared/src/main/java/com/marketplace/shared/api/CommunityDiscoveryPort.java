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
     * The newest VISIBLE posts authored by the given users (the
     * followed-sources rail's community leg — the reader's explicit
     * follow choice, never an algorithmic substitute, AC-20-05).
     */
    PagedResponse<DiscoveryPostCard> findRecentByAuthors(Set<UUID> authorIds, PagedRequest page);

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

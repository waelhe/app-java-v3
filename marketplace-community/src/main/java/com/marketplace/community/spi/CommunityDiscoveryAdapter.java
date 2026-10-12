package com.marketplace.community.spi;

import com.marketplace.community.LostFoundState;
import com.marketplace.community.NeighborhoodEvent;
import com.marketplace.community.NeighborhoodEventRepository;
import com.marketplace.community.NeighborhoodEventSpecifications;
import com.marketplace.community.NeighborhoodPost;
import com.marketplace.community.NeighborhoodPostRepository;
import com.marketplace.community.NeighborhoodPostSpecifications;
import com.marketplace.community.PostCategory;
import com.marketplace.shared.api.CommunityDiscoveryPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * JT-20 (#536 discovery waves D1-D4): the community module's
 * implementation of the {@link CommunityDiscoveryPort} cross-module read
 * contract — the discovery rails' four community legs, on the
 * {@code PostLookupAdapter} house pattern (the interface lives in
 * shared-api, the data owner implements it, the consumer injects it —
 * no cross-module repository access).
 *
 * <p><b>Eligibility-first, deterministically (AC-20):</b> every query
 * composes the module's own gates BEFORE any ordering — VISIBLE (the
 * moderation floor: a hidden post never surfaces), soft-deleted rows
 * (excluded by the entity's {@code @SoftDelete} filter — no predicate
 * of its own, by design), the neighborhood scope, the category and the
 * lifecycle state. The adapter pins each rail's deterministic order
 * itself (the D-N5 complete sort key with the id tiebreak — no shaky
 * pages): the port's contract fixes the order ("newest first" is IN the
 * contract), so a caller sort is not part of the vocabulary — the
 * {@link SpringPagination} conversion carries the page/size and the
 * adapter stamps the module's own complete key, exactly what the
 * services' {@code FEED_SORT}/{@code BOARD_SORT} discipline does.
 *
 * <p><b>The lost-and-found rail's state gate:</b> ACTIVE only — a
 * resolved or recovered report never masquerades as an active one
 * (JT-20 flow §5; {@code hasCategory(LOST_FOUND)} +
 * {@code hasLostFoundState(ACTIVE)} composed, the V173 partial index's
 * exact eligibility set).
 *
 * <p><b>The events rail is status-honest:</b> the board's own
 * {@code upcoming} floor — still-to-come AND not CANCELLED/POSTPONED —
 * so a withdrawn gathering never masquerades as upcoming, and the state
 * travels on every card so the client renders it truthfully.
 */
@Component
@Transactional(readOnly = true)
public class CommunityDiscoveryAdapter implements CommunityDiscoveryPort {

    /**
     * The followed-sources rail's hard author cap — the IN clause's
     * documented ceiling (a wider list is a loud contract violation,
     * never a silent truncation: the {@code JobListing.create} loud-guard
     * stance).
     */
    static final int MAX_AUTHOR_IDS = 100;

    /** The lost-and-found rail's order — the V173 index's own key (recency of the state, id tiebreak). */
    private static final Sort LOST_FOUND_SORT =
            Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The recommendations and followed-sources rails' order — newest first, id tiebreak (D-N5). */
    private static final Sort RECENCY_SORT =
            Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    /** The events rail's order — the board's own complete key, soonest first (D-N5). */
    private static final Sort BOARD_SORT =
            Sort.by(Sort.Direction.ASC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final NeighborhoodPostRepository postRepository;
    private final NeighborhoodEventRepository eventRepository;
    private final Clock clock;

    public CommunityDiscoveryAdapter(NeighborhoodPostRepository postRepository,
                                     NeighborhoodEventRepository eventRepository,
                                     Clock clock) {
        this.postRepository = postRepository;
        this.eventRepository = eventRepository;
        this.clock = clock;
    }

    @Override
    public PagedResponse<DiscoveryPostCard> findActiveLostFound(UUID locationId, PagedRequest page) {
        Page<NeighborhoodPost> result = postRepository.findAll(
                NeighborhoodPostSpecifications.hasLocation(locationId)
                        .and(NeighborhoodPostSpecifications.isVisible())
                        .and(NeighborhoodPostSpecifications.hasCategory(PostCategory.LOST_FOUND))
                        .and(NeighborhoodPostSpecifications.hasLostFoundState(
                                LostFoundState.ACTIVE)),
                pageableOf(page, LOST_FOUND_SORT));
        return PagedResponse.of(result.map(CommunityDiscoveryAdapter::toPostCard));
    }

    @Override
    public PagedResponse<DiscoveryPostCard> findRecommendations(UUID locationId, PagedRequest page) {
        Page<NeighborhoodPost> result = postRepository.findAll(
                NeighborhoodPostSpecifications.hasLocation(locationId)
                        .and(NeighborhoodPostSpecifications.isVisible())
                        .and(NeighborhoodPostSpecifications.hasCategory(PostCategory.RECOMMENDATION)),
                pageableOf(page, RECENCY_SORT));
        return PagedResponse.of(result.map(CommunityDiscoveryAdapter::toPostCard));
    }

    @Override
    public PagedResponse<DiscoveryEventCard> findUpcomingEvents(UUID locationId, PagedRequest page) {
        Page<NeighborhoodEvent> result = eventRepository.findAll(
                NeighborhoodEventSpecifications.hasLocation(locationId)
                        .and(NeighborhoodEventSpecifications.upcoming(clock.instant())),
                pageableOf(page, BOARD_SORT));
        return PagedResponse.of(result.map(CommunityDiscoveryAdapter::toEventCard));
    }

    @Override
    public PagedResponse<DiscoveryPostCard> findRecentByAuthors(Set<UUID> authorIds, PagedRequest page) {
        // The honest empty page: an empty whitelist is an empty answer,
        // never a query (PagedResponse.empty's own documented contract).
        if (authorIds == null || authorIds.isEmpty()) {
            return PagedResponse.empty(page);
        }
        if (authorIds.size() > MAX_AUTHOR_IDS) {
            throw new IllegalArgumentException(
                    "findRecentByAuthors accepts at most " + MAX_AUTHOR_IDS
                            + " author ids, got " + authorIds.size());
        }
        Page<NeighborhoodPost> result = postRepository.findAll(
                NeighborhoodPostSpecifications.isVisible()
                        .and((root, query, cb) -> root.get("authorId").in(authorIds)),
                pageableOf(page, RECENCY_SORT));
        return PagedResponse.of(result.map(CommunityDiscoveryAdapter::toPostCard));
    }

    /**
     * The port request's Spring conversion (the shared
     * {@link SpringPagination} interop corner) with the rail's own
     * deterministic order stamped — the port's contract fixes the order,
     * so the request's page/size are honored and the complete sort key
     * (order + id tiebreak) is always the module's.
     */
    private static Pageable pageableOf(PagedRequest request, Sort railOrder) {
        Pageable mapped = SpringPagination.toPageable(request);
        return PageRequest.of(mapped.getPageNumber(), mapped.getPageSize(), railOrder);
    }

    /** One post as the projection speaks it — the state travels only when the category carries one. */
    private static CommunityDiscoveryPort.DiscoveryPostCard toPostCard(NeighborhoodPost post) {
        return new CommunityDiscoveryPort.DiscoveryPostCard(
                post.getId(),
                post.getAuthorId(),
                post.getCategory().name(),
                post.getLostFoundState() == null ? null : post.getLostFoundState().name(),
                post.getTitle(),
                post.getBody(),
                post.getStatus().name(),
                post.getLocationId(),
                post.getUpdatedAt());
    }

    /** One event as the projection speaks it — the honest status rides every card. */
    private static CommunityDiscoveryPort.DiscoveryEventCard toEventCard(NeighborhoodEvent event) {
        return new CommunityDiscoveryPort.DiscoveryEventCard(
                event.getId(),
                event.getLocationId(),
                event.getTitle(),
                event.getDescription(),
                event.getStatus().name(),
                event.getLocationLabel(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getUpdatedAt());
    }
}

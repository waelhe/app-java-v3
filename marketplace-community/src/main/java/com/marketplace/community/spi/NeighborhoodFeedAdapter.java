package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodPostService;
import com.marketplace.community.NeighborhoodPostView;
import com.marketplace.community.PostMediaView;
import com.marketplace.shared.api.NeighborhoodFeedMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 6 (the first composite journey's feed leg): the community
 * module's implementation of the {@link NeighborhoodFeedPort}
 * cross-module read contract — the {@code CommunityDiscoveryAdapter}
 * house pattern verbatim (the data owner implements the port, the
 * consumer injects it; no cross-module repository access).
 *
 * <p><b>The adapter owns no rule of its own</b> (the inventory #599 §8:
 * REST and GraphQL must invoke ONE use case and ONE set of business
 * rules): the membership gate, the visibility floor, the neighborhood
 * scope and the complete sort key all live inside
 * {@link NeighborhoodPostService#getFeed} — the SAME use case the REST
 * feed's controller invokes. This adapter only translates the
 * pagination vocabulary (framework-neutral request → the Spring Data
 * {@code Pageable} the service speaks, through the documented
 * {@link SpringPagination} interop corner) and maps the module's
 * internal feed view into the shared transport projection
 * (field-for-field, mechanical — the same-journey field set).</p>
 */
@Component
@Transactional(readOnly = true)
public class NeighborhoodFeedAdapter implements NeighborhoodFeedPort {

    private final NeighborhoodPostService postService;

    public NeighborhoodFeedAdapter(NeighborhoodPostService postService) {
        this.postService = postService;
    }

    @Override
    public PagedResponse<NeighborhoodFeedPostView> feed(UUID callerId, PagedRequest request) {
        Pageable pageable = SpringPagination.toPageable(request);
        return PagedResponse.of(postService.getFeed(callerId, null, pageable))
                .map(NeighborhoodFeedAdapter::toSharedView);
    }

    /**
     * The internal feed view into the shared transport projection —
     * mechanical and total (every field of the same-journey set, none
     * invented, none dropped).
     */
    private static NeighborhoodFeedPostView toSharedView(NeighborhoodPostView view) {
        return new NeighborhoodFeedPostView(
                view.id(),
                view.authorId(),
                view.locationId(),
                view.category(),
                view.title(),
                view.body(),
                view.status(),
                view.declaredCommercial(),
                view.lostFoundState(),
                view.reactionsCount(),
                view.reactedByMe(),
                view.media().stream().map(NeighborhoodFeedAdapter::toSharedMedia).toList(),
                view.createdAt(),
                view.updatedAt());
    }

    /** The internal media view into its shared twin — the L48 field set, verbatim. */
    private static NeighborhoodFeedMediaView toSharedMedia(PostMediaView media) {
        return new NeighborhoodFeedMediaView(
                media.mediaId(),
                media.url(),
                media.thumbUrl(),
                media.contentType(),
                media.position());
    }
}

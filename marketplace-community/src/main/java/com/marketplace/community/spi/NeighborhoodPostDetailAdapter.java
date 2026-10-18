package com.marketplace.community.spi;

import com.marketplace.community.NeighborhoodPostService;
import com.marketplace.community.NeighborhoodPostView;
import com.marketplace.community.PostCommentView;
import com.marketplace.community.PostMediaView;
import com.marketplace.shared.api.NeighborhoodFeedMediaView;
import com.marketplace.shared.api.NeighborhoodFeedPostView;
import com.marketplace.shared.api.NeighborhoodPostCommentView;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 7 (the post/source detail composite journey's two legs): the
 * community module's implementation of the
 * {@link NeighborhoodPostDetailPort} cross-module read contract — the
 * {@code NeighborhoodFeedAdapter} house pattern verbatim (the data owner
 * implements the port, the consumer injects it; no cross-module
 * repository access).
 *
 * <p><b>The adapter owns no rule of its own</b> (the inventory #599 §8:
 * REST and GraphQL must invoke ONE use case and ONE set of business
 * rules): the post gate (the honest 404 for an unknown, hidden or
 * deleted post), the membership gate in the post's OWN neighborhood
 * (403), the visibility floor, the caller-scoped reaction facts and the
 * comment thread's complete sort key all live inside
 * {@link NeighborhoodPostService} — the SAME use cases
 * ({@code getPost}/{@code getComments}) the REST surface's controller
 * invokes. This adapter only translates the pagination vocabulary
 * (framework-neutral request → the Spring Data {@code Pageable} the
 * service speaks, through the documented {@link SpringPagination}
 * interop corner) and maps the module's internal views into the shared
 * transport projections (field-for-field, mechanical — the
 * same-journey field sets).</p>
 */
@Component
@Transactional(readOnly = true)
public class NeighborhoodPostDetailAdapter implements NeighborhoodPostDetailPort {

    private final NeighborhoodPostService postService;

    public NeighborhoodPostDetailAdapter(NeighborhoodPostService postService) {
        this.postService = postService;
    }

    @Override
    public NeighborhoodFeedPostView post(UUID callerId, UUID postId) {
        return toSharedView(postService.getPost(callerId, postId));
    }

    @Override
    public PagedResponse<NeighborhoodPostCommentView> comments(UUID callerId, UUID postId,
                                                               PagedRequest request) {
        Pageable pageable = SpringPagination.toPageable(request);
        return PagedResponse.of(postService.getComments(callerId, postId, pageable))
                .map(NeighborhoodPostDetailAdapter::toSharedComment);
    }

    /**
     * The internal post view into the shared transport projection —
     * mechanical and total (every field of the same-journey set, none
     * invented, none dropped): the IDENTICAL mapping the feed adapter
     * performs, because the detail read serves the SAME field set the
     * feed page serves (the same-journey comparison the inventory's
     * acceptance gate pins).
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
                view.media().stream().map(NeighborhoodPostDetailAdapter::toSharedMedia).toList(),
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

    /**
     * The internal comment view into the shared transport projection —
     * mechanical and total (every field of the REST comments read's own
     * set: id, postId, authorId, body, createdAt, updatedAt).
     */
    private static NeighborhoodPostCommentView toSharedComment(PostCommentView view) {
        return new NeighborhoodPostCommentView(
                view.id(),
                view.postId(),
                view.authorId(),
                view.body(),
                view.createdAt(),
                view.updatedAt());
    }
}

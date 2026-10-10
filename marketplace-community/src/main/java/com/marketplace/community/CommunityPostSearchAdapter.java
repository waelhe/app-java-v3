package com.marketplace.community;

import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.community.spi.CommunityPostSearchPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The community side of the unified search contract (§5.1) — the thin
 * adapter from the neutral SPI to {@code NeighborhoodPostService.searchFeed}
 * (the measured source: membership-scoped visibility, Arabic FTS primary,
 * pg_trgm fallback, relevance order). The adapter maps; it filters nothing,
 * re-checks nothing, and adds no axis the domain path does not already
 * enforce — the eligibility lives in the ONE place that owns it.
 *
 * <p>The hit mapping carries the honest provenance card: the stored source
 * name, the post's own publication time, its lifecycle status, its geo
 * node, and a bounded body excerpt (code-point-safe — a 200-code-point
 * ceiling never splits a surrogate pair).
 */
@Component
public class CommunityPostSearchAdapter implements CommunityPostSearchPort {

    /** The bounded excerpt ceiling (Unicode code points, surrogate-safe). */
    static final int SNIPPET_CODE_POINTS = 200;

    private final NeighborhoodPostService postService;

    public CommunityPostSearchAdapter(NeighborhoodPostService postService) {
        this.postService = postService;
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<UnifiedSearchHit> search(UUID callerId, String query, PagedRequest request) {
        Pageable pageable = PageRequest.of(request.page(), request.size());
        return PagedResponse.of(postService.searchFeed(callerId, query, null, pageable)
                .map(this::toHit));
    }

    private UnifiedSearchHit toHit(NeighborhoodPostView view) {
        return new UnifiedSearchHit(
                view.id(),
                UnifiedSearchDomain.COMMUNITY_POSTS,
                "community_post",
                view.title(),
                snippet(view.body()),
                view.status(),
                view.createdAt(),
                view.locationId());
    }

    static String snippet(String body) {
        if (body == null) {
            return null;
        }
        if (body.codePointCount(0, body.length()) <= SNIPPET_CODE_POINTS) {
            return body;
        }
        return body.substring(0, body.offsetByCodePoints(0, SNIPPET_CODE_POINTS));
    }
}

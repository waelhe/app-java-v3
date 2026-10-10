package com.marketplace.community;

import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchSource;
import com.marketplace.shared.api.UnifiedSearchSourcePort;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage 5 (community platform execution plan — the unified legal
 * multi-domain search): the community posts' contribution. The read rides
 * the repository's own Arabic-FTS unified method — the SAME visibility
 * predicates the posts' own public search applies (soft-delete + status
 * VISIBLE); this adapter adds nothing and drops nothing. The route is the
 * neighborhood post's own surface.
 */
@Component
public class CommunityPostUnifiedSearchAdapter implements UnifiedSearchSourcePort {

    static final int SNIPPET_CODE_POINTS = 160;

    private final NeighborhoodPostRepository repository;

    public CommunityPostUnifiedSearchAdapter(NeighborhoodPostRepository repository) {
        this.repository = repository;
    }

    @Override
    public UnifiedSearchSource source() {
        return UnifiedSearchSource.COMMUNITY_POST;
    }

    @Override
    public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
        return repository.searchVisibleFullTextUnified(
                        query.locationId(), query.text(), PageRequest.of(0, query.limitPerSource()))
                .stream()
                .map(post -> new UnifiedSearchHit(
                        UnifiedSearchSource.COMMUNITY_POST,
                        post.getId(),
                        post.getTitle(),
                        UnifiedSearchSourcePort.excerpt(post.getBody(), SNIPPET_CODE_POINTS),
                        post.getLocationId(),
                        "/neighborhood/posts/" + post.getId()))
                .toList();
    }
}

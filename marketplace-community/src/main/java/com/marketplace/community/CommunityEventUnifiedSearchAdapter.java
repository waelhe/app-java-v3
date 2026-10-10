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
 * multi-domain search): the community events' contribution. The read rides
 * the repository's own unified method — soft-delete applies (a withdrawn
 * event never answers), soonest-first; the location label rides the
 * snippet (an event's where-is is part of its answer).
 */
@Component
public class CommunityEventUnifiedSearchAdapter implements UnifiedSearchSourcePort {

    private final NeighborhoodEventRepository repository;

    public CommunityEventUnifiedSearchAdapter(NeighborhoodEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public UnifiedSearchSource source() {
        return UnifiedSearchSource.COMMUNITY_EVENT;
    }

    @Override
    public List<UnifiedSearchHit> search(UnifiedSearchQuery query) {
        return repository.searchLiveTextUnified(
                        query.locationId(), query.text(), PageRequest.of(0, query.limitPerSource()))
                .stream()
                .map(event -> new UnifiedSearchHit(
                        UnifiedSearchSource.COMMUNITY_EVENT,
                        event.getId(),
                        event.getTitle(),
                        UnifiedSearchSourcePort.excerpt(
                                event.getLocationLabel() == null
                                        ? event.getDescription()
                                        : event.getLocationLabel() + " — " + event.getDescription(),
                                CommunityPostUnifiedSearchAdapter.SNIPPET_CODE_POINTS),
                        event.getLocationId(),
                        "/neighborhood/events/" + event.getId()))
                .toList();
    }
}

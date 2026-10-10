package com.marketplace.search;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.SpringPagination;
import com.marketplace.shared.api.UnifiedSearchQuery;
import com.marketplace.shared.api.UnifiedSearchResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Exposes the search module's canonical orchestration to other modules
 * through the framework-neutral shared port.
 *
 * <p>This is deliberately a separate Spring bean from {@link SearchService}:
 * it must call the cacheable service method through its Spring proxy. Calling
 * that method from inside SearchService itself would be a self-invocation and
 * would bypass proxy-based cache advice.
 */
@Service
public class MarketplaceSearchAdapter implements MarketplaceSearchPort {

    private final SearchService searchService;
    private final UnifiedSearchService unifiedSearchService;

    public MarketplaceSearchAdapter(SearchService searchService,
                                    UnifiedSearchService unifiedSearchService) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
        this.unifiedSearchService = Objects.requireNonNull(unifiedSearchService,
                "unifiedSearchService must not be null");
    }

    @Override
    public PagedResponse<ListingSummary> search(SearchCriteria criteria, PagedRequest request) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Pageable pageable = SearchSorts.normalize(SpringPagination.toPageable(request));
        return PagedResponse.of(searchService.search(criteria, pageable));
    }

    @Override
    public UnifiedSearchResponse unified(UnifiedSearchQuery query) {
        Objects.requireNonNull(query, "query must not be null");
        return unifiedSearchService.search(query);
    }
}

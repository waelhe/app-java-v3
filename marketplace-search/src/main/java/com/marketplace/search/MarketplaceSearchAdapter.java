package com.marketplace.search;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.MarketplaceSearchPort;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.shared.api.SpringPagination;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Exposes the search module's canonical orchestration to other modules
 * through the framework-neutral shared port.
 *
 * <p>This is deliberately a separate Spring bean from {@link SearchService}:
 * it must call the cacheable service method through its Spring proxy.
 */
@Service
public class MarketplaceSearchAdapter implements MarketplaceSearchPort {

    private final SearchService searchService;

    public MarketplaceSearchAdapter(SearchService searchService) {
        this.searchService = Objects.requireNonNull(searchService, "searchService must not be null");
    }

    @Override
    public PagedResponse<ListingSummary> search(SearchCriteria criteria, PagedRequest request) {
        Objects.requireNonNull(criteria, "criteria must not be null");
        Objects.requireNonNull(request, "request must not be null");

        Pageable pageable = SearchSorts.normalize(SpringPagination.toPageable(request));
        return PagedResponse.of(searchService.search(criteria, pageable));
    }
}

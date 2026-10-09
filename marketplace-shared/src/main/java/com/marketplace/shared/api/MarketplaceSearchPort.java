package com.marketplace.shared.api;

/**
 * Public, framework-neutral entry point for canonical marketplace search.
 * REST and AI callers use the same orchestration rather than choosing catalog
 * repository operations independently.
 */
public interface MarketplaceSearchPort {
    PagedResponse<ListingSummary> search(SearchCriteria criteria, PagedRequest request);
}

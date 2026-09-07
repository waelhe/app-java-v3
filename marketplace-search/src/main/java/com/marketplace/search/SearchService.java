package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Search service depends on CatalogSearchPort and AvailabilityLookupPort
 * abstractions only, not on catalog or availability internals. This
 * decouples search from the providing modules.
 *
 * <p>L27 (feature-expansion roadmap §5): when the criteria carry a stay
 * window {@code [checkIn, checkOut)}, the results are restricted to the
 * providers the availability port reports as available — the bulk form of
 * {@code AvailabilityService.isAvailable}. An empty availability answer is
 * an honest empty page (total 0) without any catalog query, and the
 * restricted catalog queries apply the same restriction to their pagination
 * counts, so no page lies about its totals.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private final CatalogSearchPort catalogSearchPort;
    private final AvailabilityLookupPort availabilityLookupPort;

    public SearchService(CatalogSearchPort catalogSearchPort, AvailabilityLookupPort availabilityLookupPort) {
        this.catalogSearchPort = catalogSearchPort;
        this.availabilityLookupPort = availabilityLookupPort;
    }

    // The -v2 suffix is the ListingSummary serialization-schema namespace —
    // see CatalogService.CATALOG_CACHE_NAMES: the record gained a currency
    // component (B4), so pre-change cache entries must never be read (CodeRabbit
    // #241). Bump together with the other three names on every ListingSummary
    // component change.
    @Cacheable(cacheNames = "search-results-v2", key = "(#query == null ? '' : #query.trim()) + '|' + (#category == null ? '' : #category.trim()) + '|' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        return search(new SearchCriteria(query, category, null, null), pageable);
    }

    /**
     * The criteria path (the controller's entry). L27: the cache key is the
     * criteria record itself — its {@code toString()} covers every component
     * INCLUDING the stay window, so two different windows can never share a
     * cached entry (the acceptance criterion: extend the search-results-v2
     * key with the window). Staleness is governed by the existing
     * AFTER_COMMIT relay: listing writes evict via
     * {@code CatalogService.CATALOG_CACHE_NAMES}, availability writes evict
     * via {@code AvailabilityService}'s invalidation set.
     */
    @Cacheable(cacheNames = "search-results-v2", key = "(#criteria == null ? '' : #criteria.toString()) + '|' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> search(SearchCriteria criteria, Pageable pageable) {
        if (criteria.hasWindow()) {
            return searchWindowed(criteria, pageable);
        }
        return searchUnwindowed(criteria, pageable);
    }

    /**
     * L27 window path: restrict to the providers that are available for the
     * window, then run the ordinary branch dispatch against the restricted
     * catalog queries.
     */
    private Page<ListingSummary> searchWindowed(SearchCriteria criteria, Pageable pageable) {
        Set<java.util.UUID> availableProviderIds =
                availabilityLookupPort.findAvailableProviderIds(criteria.checkIn(), criteria.checkOut());
        if (availableProviderIds.isEmpty()) {
            // Nobody qualifies — an honest empty page (total 0), no query.
            return Page.empty(pageable);
        }
        String query = criteria.query();
        if (query != null && !query.isBlank()) {
            return catalogSearchPort.searchFullTextRestricted(query.trim(), availableProviderIds, pageable);
        }
        // Covers the price / category / browse-all branches: they are the
        // optional predicates of one criteria query.
        return catalogSearchPort.searchByCriteriaRestricted(criteria, availableProviderIds, pageable);
    }

    /** The pre-L27 dispatch — byte-identical for windowless criteria. */
    private Page<ListingSummary> searchUnwindowed(SearchCriteria criteria, Pageable pageable) {
        String query = criteria.query();
        String category = criteria.category();
        if (query != null && !query.isBlank()) {
            // Raw user input passed through: the official websearch_to_tsquery
            // (ProviderListingRepository) parses it leniently and supports
            // "quoted phrases", OR and -exclusion. The former hand-mangling
            // (replaceAll("\\s+", " & ")) both corrupted the user's phrase
            // intent and fed to_tsquery invalid syntax for quotes/parens/dashes
            // (SQL exception -> HTTP 500).
            return catalogSearchPort.searchFullText(query.trim(), pageable);
        }
        if (criteria.minPrice() != null || criteria.maxPrice() != null) {
            return catalogSearchPort.searchByCriteria(criteria, pageable);
        }
        if (category != null && !category.isBlank()) {
            return catalogSearchPort.listByCategory(category, pageable);
        }
        return catalogSearchPort.listActive(pageable);
    }

    public Page<ListingSummary> searchByCategory(String category, Pageable pageable) {
        return catalogSearchPort.listByCategory(category, pageable);
    }

    public Page<ListingSummary> searchAll(Pageable pageable) {
        return catalogSearchPort.listActive(pageable);
    }
}

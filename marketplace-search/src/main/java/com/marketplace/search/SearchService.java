package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Search service depends on CatalogSearchPort abstraction only,
 * not on catalog internals. This decouples search from catalog module.
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private final CatalogSearchPort catalogSearchPort;

    public SearchService(CatalogSearchPort catalogSearchPort) {
        this.catalogSearchPort = catalogSearchPort;
    }

    @Cacheable(cacheNames = "search-results", key = "(#criteria.query() == null ? '' : #criteria.query().trim()) + '|' + (#criteria.category() == null ? '' : #criteria.category().trim()) + '|' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> search(SearchCriteria criteria, Pageable pageable) {
        SearchCriteria normalized = criteria.query() == null || criteria.query().isBlank()
                ? criteria
                : new SearchCriteria(criteria.query().trim().replaceAll("\\s+", " & "), criteria.category(), criteria.minPriceCents(),
                        criteria.maxPriceCents(), criteria.minRating(), criteria.location(), criteria.availableFrom(), criteria.availableTo(), criteria.sort());
        return catalogSearchPort.search(normalized, pageable);
    }

    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        return search(new SearchCriteria(query, category, null, null, null, null, null, null, null), pageable);
    }

    public Page<ListingSummary> searchByCategory(String category, Pageable pageable) {
        return catalogSearchPort.listByCategory(category, pageable);
    }

    public Page<ListingSummary> searchAll(Pageable pageable) {
        return catalogSearchPort.listActive(pageable);
    }
}

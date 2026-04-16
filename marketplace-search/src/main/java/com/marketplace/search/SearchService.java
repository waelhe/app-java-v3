package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
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

    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        if (query != null && !query.isBlank()) {
            String tsQuery = query.trim().replaceAll("\\s+", " & ");
            return catalogSearchPort.searchFullText(tsQuery, pageable);
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

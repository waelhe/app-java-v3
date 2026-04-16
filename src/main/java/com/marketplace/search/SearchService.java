package com.marketplace.search;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.ListingSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private final CatalogService catalogService;

    public SearchService(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        if (query != null && !query.isBlank()) {
            String tsQuery = query.trim().replaceAll("\\s+", " & ");
            return catalogService.searchFullText(tsQuery, pageable);
        }
        if (category != null && !category.isBlank()) {
            return catalogService.listByCategorySummary(category, pageable);
        }
        return catalogService.listActiveSummary(pageable);
    }

    public Page<ListingSummary> searchByCategory(String category, Pageable pageable) {
        return catalogService.listByCategorySummary(category, pageable);
    }

    public Page<ListingSummary> searchAll(Pageable pageable) {
        return catalogService.listActiveSummary(pageable);
    }
}

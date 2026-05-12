package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Port interface for catalog search operations.
 * Decouples search module from catalog internals — search depends on this
 * abstraction in shared-api, while catalog provides the implementation.
 */
public interface CatalogSearchPort {

    Page<ListingSummary> searchFullText(String tsQuery, Pageable pageable);

    Page<ListingSummary> listByCategory(String category, Pageable pageable);

    Page<ListingSummary> listActive(Pageable pageable);

    Page<ListingSummary> searchByCriteria(SearchCriteria criteria, Pageable pageable);
}

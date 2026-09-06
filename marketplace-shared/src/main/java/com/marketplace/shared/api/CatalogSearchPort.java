package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Port interface for catalog search operations.
 * Decouples search module from catalog internals — search depends on this
 * abstraction in shared-api, while catalog provides the implementation.
 */
public interface CatalogSearchPort {

    /**
     * Full-text search over listing title and description.
     *
     * @param query raw user input — parsed by the official PostgreSQL
     *              {@code websearch_to_tsquery}, which accepts unformatted text
     *              and the web-search operators {@code "quoted phrase"},
     *              {@code OR} and {@code -exclusion}. Never pre-mangled by
     *              callers; arbitrary special characters are not an error.
     */
    Page<ListingSummary> searchFullText(String query, Pageable pageable);

    Page<ListingSummary> listByCategory(String category, Pageable pageable);

    Page<ListingSummary> listActive(Pageable pageable);

    Page<ListingSummary> searchByCriteria(SearchCriteria criteria, Pageable pageable);
}

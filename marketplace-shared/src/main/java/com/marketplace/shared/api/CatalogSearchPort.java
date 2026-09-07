package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Set;
import java.util.UUID;

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

    // L27 (feature-expansion roadmap §5) — window-restricted variants. The
    // providerIds set is the server-derived availability whitelist (see
    // {@link AvailabilityLookupPort}); the contract is: the caller has already
    // handled the empty-whitelist case (honest empty page, no query), so the
    // collection arriving here is non-empty. The pagination count applies the
    // same restriction as the content query ("no deceptive pages").

    /**
     * Full-text search restricted to the given providers — same ranking and
     * the same typo-tolerance fallback as {@link #searchFullText}, plus the
     * {@code provider_id} restriction.
     */
    Page<ListingSummary> searchFullTextRestricted(String query, Set<UUID> providerIds, Pageable pageable);

    /**
     * Criteria search restricted to the given providers — covers the
     * price / category / browse-all branches (the optional predicates of the
     * criteria query), plus the {@code provider_id} restriction.
     */
    Page<ListingSummary> searchByCriteriaRestricted(SearchCriteria criteria, Set<UUID> providerIds, Pageable pageable);
}

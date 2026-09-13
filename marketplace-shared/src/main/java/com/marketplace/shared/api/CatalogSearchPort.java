package com.marketplace.shared.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
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

    // L32 (realestate systems plan §5) — the property-restriction branches.
    // The restricted-to-LISTINGS forms carry the realestate module's
    // matching-id set (RealestatePropertyFilterPort) in the same shape the
    // provider-restricted forms carry the availability whitelist: the
    // caller has already handled the empty-set case (honest empty page, no
    // query), and the pagination count applies the same restriction as the
    // content query ("no deceptive pages"). The Specification-backed
    // implementation also honors the Pageable sort (the plan's price /
    // newest sort whitelist) with the id tiebreak.

    /**
     * Criteria search restricted to the given listing ids — the
     * property-facet flow. Sort-aware: the effective sort (price/newest,
     * default id ASC) is honored deterministically.
     */
    Page<ListingSummary> searchByCriteriaRestrictedToListings(SearchCriteria criteria, Set<UUID> listingIds, Pageable pageable);

    /**
     * L32: the sort-aware criteria search WITHOUT a restriction — the
     * Specification path for plain filter searches that carry a
     * price/newest sort (the native criteria query's baked ORDER BY id
     * cannot honor a sort). Same optional predicates (category/price/
     * guests) as {@link #searchByCriteria}; the unsorted form is NOT
     * routed here (the legacy native path stays byte-identical).
     */
    Page<ListingSummary> searchByCriteriaFaceted(SearchCriteria criteria, Pageable pageable);

    /**
     * Full-text search restricted to the given listing ids — same official
     * ranking ({@code ts_rank} DESC, id tiebreak) and the same pg_trgm
     * typo-tolerance fallback as {@link #searchFullText}, plus the
     * {@code id} restriction. Text searches rank by relevance: the facet
     * sort whitelist does not apply (documented).
     */
    Page<ListingSummary> searchFullTextRestrictedToListings(String query, Set<UUID> listingIds, Pageable pageable);

    /**
     * The ids of every ACTIVE listing (soft-delete filtered) — the
     * ACTIVE-set restriction the area-sorted flow passes into the
     * realestate port (the realestate table does not know listing status
     * by design).
     */
    Set<UUID> findActiveListingIds();

    /**
     * CodeRabbit PR #300 round 1: the ids of every ACTIVE listing matching
     * the criteria's optional CATALOG predicates (category / price bounds /
     * guests) — the criteria-ELIGIBLE set the paged realestate flows (the
     * {@code area} and {@code distance} orderings) intersect before paging.
     * Those flows cannot apply the catalog predicates DB-side: their
     * ordering and their predicates are realestate-owned
     * ({@code property_details}), so the catalog side resolves its own
     * eligibility first. The result is ACTIVE-gated by construction (the
     * shared criteria specification carries the ACTIVE status predicate),
     * so it is a subset of {@link #findActiveListingIds()} — the caller
     * intersects it (with the facet set and any other restriction) instead
     * of the bare ACTIVE set.
     */
    Set<UUID> findActiveListingIdsMatching(SearchCriteria criteria);

    /**
     * The listing summaries for the given ids, returned in the GIVEN order
     * (the area-sorted page assembly). Ids that no longer resolve to an
     * ACTIVE listing are skipped (the page total comes from the property
     * side).
     */
    List<ListingSummary> findSummariesByIds(List<UUID> idsInOrder);
}

package com.marketplace.shared.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Port interface for catalog search operations.
 * Decouples search module from catalog internals — search depends on this
 * abstraction in shared-api, while catalog provides the implementation.
 *
 * <p><b>Neutral pagination (the boundary contract):</b> the paged forms
 * speak {@link PagedRequest} and answer {@link PagedResponse} — no
 * Spring Data type crosses this interface (the audit's finding that the
 * shared ports leaked framework types is repaired here and pinned by
 * the {@code sharedPortsAreFrameworkNeutral} architecture rule). The
 * catalog adapter maps the neutral request onto Spring Data pagination
 * internally; the sort vocabulary the search surface sends (mapped
 * {@code priceCents}/{@code createdAt} with the {@code id} tiebreak)
 * rides the request's ordering steps verbatim.
 */
public interface CatalogSearchPort {

    /**
     * Full-text search over listing title and description.
     *
     * <p><b>R6 (comprehensive-review-ar fix plan §4, Wave 5 — the composed
     * text+filter search):</b> the contract carries the FULL criteria —
     * the text query AND the optional catalog predicates (category /
     * price bounds / guests) compose into ONE query whose count and
     * pagination apply the same restriction. The former text-only
     * contract let a text query silently drop every riding filter (the
     * review's R6: a {@code q + category + maxPrice + guests} request
     * answered the unfiltered text match set).</p>
     *
     * @param criteria the full search criteria — {@code criteria.query()}
     *                 is the raw user input parsed by the official
     *                 PostgreSQL {@code websearch_to_tsquery}, which
     *                 accepts unformatted text and the web-search
     *                 operators {@code "quoted phrase"}, {@code OR} and
     *                 {@code -exclusion}. Never pre-mangled by callers;
     *                 arbitrary special characters are not an error. The
     *                 optional predicates ride the same native query's
     *                 predicate blocks.
     */
    PagedResponse<ListingSummary> searchFullText(SearchCriteria criteria, PagedRequest request);

    // W6 (search-unit compliance pass): every text form of this port
    // (searchFullText, searchFullTextRestricted,
    // searchFullTextRestrictedToListings) ranks by RELEVANCE — the
    // implementation strips any sort the request carries to the page/size
    // its native queries consume (their ORDER BY is their own complete
    // contract: the unified boost flag, ts_rank/word_similarity, the id
    // tiebreak). A sorted request through a text form is served the
    // documented relevance ranking, never a broken query — the same law
    // the realestate radius flow's distancePaged() applies to its baked
    // distance ordering.

    PagedResponse<ListingSummary> listByCategory(String category, PagedRequest request);

    PagedResponse<ListingSummary> listActive(PagedRequest request);

    PagedResponse<ListingSummary> searchByCriteria(SearchCriteria criteria, PagedRequest request);

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
     *
     * <p><b>R6 (Wave 5):</b> carries the FULL criteria like
     * {@link #searchFullText} — the text query composes with the optional
     * catalog predicates inside the same restricted query (and its
     * count), instead of dropping them.</p>
     */
    PagedResponse<ListingSummary> searchFullTextRestricted(SearchCriteria criteria, Set<UUID> providerIds, PagedRequest request);

    /**
     * Criteria search restricted to the given providers — covers the
     * price / category / browse-all branches (the optional predicates of the
     * criteria query), plus the {@code provider_id} restriction.
     *
     * <p><b>W6 (search-unit compliance pass):</b> sort-aware — the mapped
     * sort vocabulary the request carries (priceCents/createdAt with the id
     * tiebreak) is honored on the Specification-backed implementation; the
     * unsorted form keeps the deterministic boost-first + id order.</p>
     */
    PagedResponse<ListingSummary> searchByCriteriaRestricted(SearchCriteria criteria, Set<UUID> providerIds, PagedRequest request);

    // L32 (realestate systems plan §5) — the property-restriction branches.
    // The restricted-to-LISTINGS forms carry the realestate module's
    // matching-id set (RealestatePropertyFilterPort) in the same shape the
    // provider-restricted forms carry the availability whitelist: the
    // caller has already handled the empty-set case (honest empty page, no
    // query), and the pagination count applies the same restriction as the
    // content query ("no deceptive pages"). The Specification-backed
    // implementation also honors the request's ordering steps (the plan's
    // price / newest sort whitelist) with the id tiebreak.

    /**
     * Criteria search restricted to the given listing ids — the
     * property-facet flow. Sort-aware: the effective sort (price/newest,
     * default id ASC) is honored deterministically.
     */
    PagedResponse<ListingSummary> searchByCriteriaRestrictedToListings(SearchCriteria criteria, Set<UUID> listingIds, PagedRequest request);

    /**
     * L32: the sort-aware criteria search WITHOUT a restriction — the
     * Specification path for plain filter searches that carry a
     * price/newest sort (the native criteria query's baked ORDER BY id
     * cannot honor a sort). Same optional predicates (category/price/
     * guests) as {@link #searchByCriteria}; the unsorted form is NOT
     * routed here (the legacy native path stays byte-identical).
     */
    PagedResponse<ListingSummary> searchByCriteriaFaceted(SearchCriteria criteria, PagedRequest request);

    /**
     * Full-text search restricted to the given listing ids — same official
     * ranking ({@code ts_rank} DESC, id tiebreak) and the same pg_trgm
     * typo-tolerance fallback as {@link #searchFullText}, plus the
     * {@code id} restriction. Text searches rank by relevance: the facet
     * sort whitelist does not apply (documented).
     *
     * <p><b>R6 (Wave 5):</b> carries the FULL criteria like
     * {@link #searchFullText} — the text query composes with the optional
     * catalog predicates inside the same id-restricted query (and its
     * count), instead of dropping them. The property flow's text branch
     * and the saved-search matcher's membership probe ride this
     * composition.</p>
     */
    PagedResponse<ListingSummary> searchFullTextRestrictedToListings(SearchCriteria criteria, Set<UUID> listingIds, PagedRequest request);

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

    /**
     * L36 (realestate systems plan §5 — agent/office pages): one provider's
     * ACTIVE listings, paginated — the public provider page's listings
     * block. The provider status gate is the CALLER's rule (the provider
     * module hides the block for non-VERIFIED profiles); this port serves
     * the same documented public contract as the REST surface
     * {@code GET /api/v1/listings/provider/{providerId}} (the CWE-200
     * adoption: ACTIVE only, soft-delete filtered).
     *
     * <p><b>Id space (the AuthHelper A1 contract):</b> the argument is the
     * provider's USER id — {@code provider_listings.provider_id} carries
     * {@code users.id}, exactly like every cross-module provider_id column
     * (availability, ledger, booking). The caller resolves the profile row
     * and passes {@code profile.getUserId()}.
     */
    PagedResponse<ListingSummary> listActiveByProvider(UUID providerUserId, PagedRequest request);
}

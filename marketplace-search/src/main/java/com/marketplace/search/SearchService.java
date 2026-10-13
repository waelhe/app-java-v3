package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SpringPagination;
import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Search service depends on the shared-api ports only, not on catalog /
 * availability / geo / realestate internals. This decouples search from
 * the providing modules.
 *
 * <p>L27 (feature-expansion roadmap §5): when the criteria carry a stay
 * window {@code [checkIn, checkOut)}, the results are restricted to the
 * providers the availability port reports as available — the bulk form of
 * {@code AvailabilityService.isAvailable}. An empty availability answer is
 * an honest empty page (total 0) without any catalog query, and the
 * restricted catalog queries apply the same restriction to their pagination
 * counts, so no page lies about its totals.
 *
 * <p>L32 (realestate systems plan §5): the faceted path. The real-estate
 * criteria (locationId/purpose/propertyType/minRooms/minBathrooms/
 * minAreaM2 — gated by the SearchCriteria record) resolve through the
 * ports, never through cross-module queries:
 * <ol>
 *   <li>{@code locationId} → the geo port's qualified set
 *       (self + descendants — "the search does not fiddle with tree
 *       depth"; unknown id = 404);</li>
 *   <li>the property criteria + the qualified set → the realestate filter
 *       port's matching LISTING-ID SET (the adopted CodeRabbit
 *       architecture — the set-restriction pattern in the existing
 *       restricted-branch shape); an empty answer is an honest empty page
 *       without any catalog query;</li>
 *   <li>the catalog query composes the restriction (id IN) — Specification-
 *       backed for the criteria path (sort-aware: price/newest + id
 *       tiebreak), native FTS + trgm fallback for the text path
 *       (relevance-ranked, documented).</li>
 * </ol>
 *
 * <p>The area sort (the realestate-owned column) composes differently:
 * the realestate port PAGES through its own area ordering restricted to
 * the catalog's ACTIVE id set, and this service assembles the page from
 * the catalog summaries in that order — each module owns its schema, the
 * search owns the composition. Text queries rank by relevance: the area
 * sort is ignored for them (documented at the surface).
 */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private final CatalogSearchPort catalogSearchPort;
    private final AvailabilityLookupPort availabilityLookupPort;
    private final GeoLookupPort geoLookupPort;
    private final RealestatePropertyFilterPort realestatePropertyFilterPort;

    public SearchService(CatalogSearchPort catalogSearchPort,
                         AvailabilityLookupPort availabilityLookupPort,
                         GeoLookupPort geoLookupPort,
                         RealestatePropertyFilterPort realestatePropertyFilterPort) {
        this.catalogSearchPort = catalogSearchPort;
        this.availabilityLookupPort = availabilityLookupPort;
        this.geoLookupPort = geoLookupPort;
        this.realestatePropertyFilterPort = realestatePropertyFilterPort;
    }

    /**
     * ADR-0011 (D-15 — DSA Art. 27(1)/(2) + Art. 26(1)(d)): the machine
     * truth of the ranking — the public read the terms and conditions cite.
     * Reference data (the categories-registry pattern): a closed shape
     * composed from the modules' documented laws, never a free-form store.
     */
    @Transactional(readOnly = true)
    public RankingParametersView rankingParameters() {
        return RankingParametersView.thePlatformTruth();
    }

    // W6 (search-unit compliance pass): the legacy two-argument
    // search(query, category, pageable) overload was REMOVED. Its
    // @Cacheable key was a hand-rolled SpEL concatenation
    // (query + '|' + category + '|' + page/size/sort) — the exact
    // injectivity defect the dedicated SearchCriteriaCacheKeyGenerator
    // was adopted to close (PR #256 round 1: two different
    // (query, category) pairs can produce the SAME concatenated string —
    // query="a|b" + category="c" collides with query="a" +
    // category="b|c" — and one request is served the other's cached
    // page). It had no production caller (the controller binds the
    // criteria form; its own javadoc said so) and no test caller, so the
    // removal is behavior-neutral for every live surface: the criteria
    // form below is the single entry into the search-results-v6 cache,
    // keyed exclusively through the generator. The criteria path is the
    // ONE surface (the same law the catalog's CATALOG_CACHE_NAMES set
    // documents for its own names).

    /**
     * The criteria path (the controller's entry). L27: the cache key comes
     * from the dedicated {@link SearchCriteriaCacheKeyGenerator} — an
     * injective, length-prefixed component key. L32: the six new criteria
     * components ride as first-class key segments (the generator's
     * l32v1 schema), and the property resolution (geo set + filter set)
     * happens INSIDE the cached method — deterministic from the criteria,
     * so the key stays complete. Staleness is governed by the existing
     * AFTER_COMMIT relay: listing writes evict via
     * {@code CatalogService.CATALOG_CACHE_NAMES}, availability writes evict
     * via {@code AvailabilityService}'s invalidation set, geo writes evict
     * geo-tree (the tree read, not the search pages — location criteria are
     * resolved per request, see below) and property writes evict through
     * the realestate module's relay registration.
     *
     * <p>P1 (postgis plan): the radius triple rides as first-class key
     * segments too (the generator's l34v1 schema — canonical scale-6
     * coordinates and whole-meter radius); the radius resolution (the
     * ST_DWithin set and the distance-ordered pages) happens INSIDE the
     * cached method — deterministic from the criteria, same completeness
     * discipline as the L32 facets. Property-coordinate writes evict
     * through the realestate module's relay registration (the same
     * registration the L32 facets use — a coordinate change is a property
     * change).
     */
    @Cacheable(cacheNames = "search-results-v6", keyGenerator = "searchCriteriaKeyGenerator")
    public Page<ListingSummary> search(SearchCriteria criteria, Pageable pageable) {
        // P1 (postgis plan): the radius branch — hasRadius() pushes to the
        // dedicated dispatch exactly like hasPropertyCriteria() does for
        // the L32 facets. sort=distance without the radius criteria is a
        // 400 HERE (before any query): a distance order is meaningless
        // without a center, and the marker must never leak into a legacy
        // path (the L32 lesson — an unconsumed sort marker is a SQL error
        // waiting downstream, not a sort).
        if (criteria.hasRadius() || SearchSorts.isDistanceSorted(pageable)) {
            if (!criteria.hasRadius()) {
                throw new com.marketplace.shared.api.BadRequestException(
                        "sort=distance requires the radius criteria (lat, lng, radiusKm)");
            }
            return dispatchRadius(criteria, pageable);
        }
        // CodeRabbit PR #299 round 1 (two findings, one root), reconciled
        // with the P1 radius branch:
        // (a) the property flow was selected for an IGNORED text-search
        //     sort — a text query with sort=area restricted the full-text
        //     search to property-bearing listings although the documented
        //     behavior is "text searches ignore the sort": the area marker
        //     selects the property flow ONLY for blank queries;
        // (b) the controller is the SINGLE normalization point — the
        //     pageable arrives MAPPED (priceCents/createdAt + the id
        //     tiebreak), so the branch checks and every downstream call
        //     consume that one representation (a second normalize() call
        //     would reject the already-mapped names — 400).
        boolean textQuery = criteria.query() != null && !criteria.query().isBlank();
        boolean propertyFlow = criteria.hasPropertyCriteria()
                || (SearchSorts.isAreaSorted(pageable) && !textQuery);
        if (propertyFlow) {
            return dispatchProperty(criteria, pageable);
        }
        if (hasMappedSort(pageable) && !criteria.hasWindow()) {
            // L32: a price/newest sort on a plain (unwindowed) filter search
            // rides the Specification path — sort-aware by construction.
            // W6 (search-unit compliance pass): the WINDOWED filter search
            // honors the sort the same way now — the restricted criteria
            // query is Specification-backed (the retired native twin's
            // baked ORDER BY was the reason the sort used to be a scope
            // boundary; the boundary is closed). Text queries rank by
            // relevance — the sort is ignored (documented; the catalog
            // adapter strips it structurally).
            String query = criteria.query();
            if (query == null || query.isBlank()) {
                // already normalized at the controller — consumed as-is
                return SpringPagination.toPage(catalogSearchPort.searchByCriteriaFaceted(criteria, SpringPagination.toPagedRequest(pageable)), pageable);
            }
            // text queries rank by relevance — the sort is ignored (documented)
        }
        // the pre-L32 dispatch — byte-identical for legacy criteria
        return dispatchLegacy(criteria, pageable);
    }

    /**
     * Whether the (controller-normalized) sort requests a mapped property —
     * the MAPPED names (priceCents/createdAt), the representation the
     * controller's single normalize() delivers.
     */
    private static boolean hasMappedSort(Pageable pageable) {
        return pageable.getSort().stream()
                .anyMatch(order -> "priceCents".equals(order.getProperty())
                        || "createdAt".equals(order.getProperty())
                        // W3 (G16): the rating sort's mapped name — the
                        // composite column rides the same Specification
                        // path (the legacy native criteria query's baked
                        // ORDER BY cannot honor it; the faceted path's
                        // boostFirst composes the ranked-first tier).
                        || "rankingScore".equals(order.getProperty()));
    }

    /** The pre-L32 dispatch — verbatim. */
    private Page<ListingSummary> dispatchLegacy(SearchCriteria criteria, Pageable pageable) {
        if (criteria.hasWindow()) {
            return searchWindowed(criteria, pageable);
        }
        return searchUnwindowed(criteria, pageable);
    }

    /**
     * The L32 faceted dispatch: resolve the location set (404 for an
     * unknown id — never a silently-empty page), the availability
     * whitelist (window path), then either the area-sorted paged flow or
     * the set-restricted catalog queries.
     */
    private Page<ListingSummary> dispatchProperty(SearchCriteria criteria, Pageable pageable) {
        Set<UUID> locationIds = criteria.locationId() != null
                ? geoLookupPort.findSelfAndDescendants(criteria.locationId())
                : null;
        PropertyCriteria propertyCriteria = toPropertyCriteria(criteria, locationIds);

        Set<UUID> providerIds = null;
        if (criteria.hasWindow()) {
            providerIds = availabilityLookupPort.findAvailableProviderIds(
                    criteria.checkIn(), criteria.checkOut());
            if (providerIds.isEmpty()) {
                return Page.empty(pageable); // honest empty page, no query
            }
        }

        String query = criteria.query();
        boolean textQuery = query != null && !query.isBlank();

        if (SearchSorts.isAreaSorted(pageable) && !textQuery) {
            return searchAreaSorted(criteria, propertyCriteria, providerIds, pageable);
        }

        Set<UUID> listingIds = providerIds != null
                ? realestatePropertyFilterPort.findListingIdsMatchingRestricted(propertyCriteria, providerIds)
                : realestatePropertyFilterPort.findListingIdsMatching(propertyCriteria);
        if (listingIds.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no catalog query
        }

        if (textQuery) {
            // R6 (Wave 5): the full criteria compose into the
            // id-restricted text query — the catalog predicates ride
            // along with the property-facet restriction.
            return SpringPagination.toPage(catalogSearchPort.searchFullTextRestrictedToListings(
                    criteria, listingIds, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        return SpringPagination.toPage(catalogSearchPort.searchByCriteriaRestrictedToListings(
                criteria, listingIds, SpringPagination.toPagedRequest(pageable)), pageable);
    }

    /**
     * The area-sorted flow (browse/filter searches — text queries rank by
     * relevance and ignore the area sort, documented): the realestate port
     * pages through its own area ordering restricted to the caller-resolved
     * eligible set; the summaries are fetched in that order and the page
     * total is the property side's (the counts cannot lie).
     *
     * <p>CodeRabbit PR #300 round 1 (thread 3's class applied to the L32
     * instance — a same-class discovery): the eligible set is the
     * criteria-ELIGIBLE ACTIVE id set (category/price/guests resolved on
     * the catalog's side) instead of the bare ACTIVE set — the paged form's
     * predicates are realestate-owned, so a {@code guests=4&sort=area}
     * request previously returned listings that cannot accommodate four
     * guests (the identical gap the review flagged on the distance flow;
     * fixed by the same root, not left one branch away).
     */
    private Page<ListingSummary> searchAreaSorted(SearchCriteria criteria,
                                                  PropertyCriteria propertyCriteria,
                                                  Set<UUID> providerIds, Pageable pageable) {
        Set<UUID> eligibleIds = criteria.hasCatalogCriteria()
                ? catalogSearchPort.findActiveListingIdsMatching(criteria)
                : catalogSearchPort.findActiveListingIds();
        PagedResponse<RealestatePropertyFilterPort.PropertyMatch> matches = providerIds != null
                ? realestatePropertyFilterPort.findMatchingPagedRestricted(
                        propertyCriteria, eligibleIds, providerIds, SpringPagination.toPagedRequest(pageable))
                : realestatePropertyFilterPort.findMatchingPaged(
                        propertyCriteria, eligibleIds, SpringPagination.toPagedRequest(pageable));

        if (matches.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, matches.totalElements());
        }
        List<UUID> orderedIds = matches.content().stream()
                .map(RealestatePropertyFilterPort.PropertyMatch::listingId)
                .toList();
        List<ListingSummary> summaries = catalogSearchPort.findSummariesByIds(orderedIds);
        // order already preserved by findSummariesByIds; total from the
        // property side (the restriction that defines the page)
        return new PageImpl<>(summaries, pageable, matches.totalElements());
    }

    /**
     * P1 (postgis plan): the radius dispatch — hasRadius() pushed here (or
     * the distance-sort marker did, which always implies the radius). The
     * composition mirrors dispatchProperty: the window whitelist first
     * (an empty whitelist is an honest empty page, no query), then either
     * the distance-ordered paged flow (non-text) or the set flow — the
     * ST_DWithin listing-id set INTERSECTED with the facet set when
     * real-estate facets ride along (D-P10: the radius is an ADDITIONAL
     * criterion that ANDs with the geo hierarchy and the rest), then the
     * restricted catalog queries in the existing shapes. Text queries rank
     * by relevance — the distance sort is ignored for them (the documented
     * scope boundary, same as the area sort).
     */
    private Page<ListingSummary> dispatchRadius(SearchCriteria criteria, Pageable pageable) {
        Set<UUID> providerIds = null;
        if (criteria.hasWindow()) {
            providerIds = availabilityLookupPort.findAvailableProviderIds(
                    criteria.checkIn(), criteria.checkOut());
            if (providerIds.isEmpty()) {
                return Page.empty(pageable); // honest empty page, no query
            }
        }
        String query = criteria.query();
        boolean textQuery = query != null && !query.isBlank();

        if (SearchSorts.isDistanceSorted(pageable) && !textQuery) {
            return searchDistanceSorted(criteria, providerIds, pageable);
        }
        // CodeRabbit PR #300 round 1 (thread 2 — the area marker's radius
        // flow): an area-sorted radius request is NOT the set flow — the
        // marker cannot ride searchByCriteriaRestrictedToListings (the
        // Specification path would sort by a non-existent catalog "area"
        // property), and the pre-fix code's second normalize() rejected
        // the controller-mapped marker+id pair as a "mixed marker" 400. It
        // routes to the realestate-owned paged flow (the searchAreaSorted
        // composition with the radius restriction), exactly like the
        // distance marker above it. Text queries rank by relevance — the
        // area sort is ignored for them (the documented boundary, same as
        // every marker).
        if (SearchSorts.isAreaSorted(pageable) && !textQuery) {
            return searchRadiusAreaSorted(criteria, providerIds, pageable);
        }

        Set<UUID> listingIds = providerIds != null
                ? realestatePropertyFilterPort.findListingIdsWithinRadiusRestricted(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters(), providerIds)
                : realestatePropertyFilterPort.findListingIdsWithinRadius(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters());
        if (listingIds.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no catalog query
        }

        // D-P10: the radius ANDs with the geo hierarchy and the facets —
        // the set-restriction composition (the facet set resolved the same
        // way dispatchProperty resolves it, provider-restricted on the
        // window path, then intersected).
        if (criteria.hasPropertyCriteria()) {
            Set<UUID> locationIds = criteria.locationId() != null
                    ? geoLookupPort.findSelfAndDescendants(criteria.locationId())
                    : null;
            PropertyCriteria propertyCriteria = toPropertyCriteria(criteria, locationIds);
            Set<UUID> facetIds = providerIds != null
                    ? realestatePropertyFilterPort.findListingIdsMatchingRestricted(propertyCriteria, providerIds)
                    : realestatePropertyFilterPort.findListingIdsMatching(propertyCriteria);
            listingIds = listingIds.stream()
                    .filter(facetIds::contains)
                    .collect(Collectors.toSet());
            if (listingIds.isEmpty()) {
                return Page.empty(pageable); // honest empty page, no catalog query
            }
        }

        if (textQuery) {
            // R6 (Wave 5): the full criteria compose into the
            // id-restricted text query — the catalog predicates ride
            // along with the radius restriction.
            return SpringPagination.toPage(catalogSearchPort.searchFullTextRestrictedToListings(
                    criteria, listingIds, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        // CodeRabbit PR #300 round 1 (thread 2 — normalize ONCE): the
        // controller is the single normalization point — the pageable
        // arrives MAPPED (priceCents/createdAt + the id tiebreak) and the
        // restricted Specification path honors it deterministically. The
        // former second normalize() here rejected the already-mapped names
        // ("unsupported sort property: priceCents") — a price/newest-sorted
        // radius request answered 400 instead of a sorted page. The area
        // and distance markers never reach this line (both routed above).
        return SpringPagination.toPage(catalogSearchPort.searchByCriteriaRestrictedToListings(
                criteria, listingIds, SpringPagination.toPagedRequest(pageable)), pageable);
    }

    /**
     * The distance-sorted flow ({@code sort=distance}, non-text): the
     * searchAreaSorted composition adapted to the native radius ordering —
     * the facet set (when facets ride along) resolves first through the
     * port's Specification forms, the catalog's criteria-ELIGIBLE id set
     * (ACTIVE + category/price/guests — CodeRabbit PR #300 round 1, thread 3)
     * intersects it, and the realestate port pages through its own
     * ST_Distance ordering restricted to that intersection; the summaries
     * are fetched in that order and the page total is the radius flow's
     * own (the counts cannot lie). The distance itself never leaves the
     * port (D-P11) — the answer is listing ids in nearest-first order.
     */
    private Page<ListingSummary> searchDistanceSorted(SearchCriteria criteria,
                                                      Set<UUID> providerIds, Pageable pageable) {
        Set<UUID> facetIds = null;
        if (criteria.hasPropertyCriteria()) {
            Set<UUID> locationIds = criteria.locationId() != null
                    ? geoLookupPort.findSelfAndDescendants(criteria.locationId())
                    : null;
            PropertyCriteria propertyCriteria = toPropertyCriteria(criteria, locationIds);
            facetIds = providerIds != null
                    ? realestatePropertyFilterPort.findListingIdsMatchingRestricted(propertyCriteria, providerIds)
                    : realestatePropertyFilterPort.findListingIdsMatching(propertyCriteria);
            if (facetIds.isEmpty()) {
                return Page.empty(pageable); // honest empty page, no query
            }
        }
        // CodeRabbit PR #300 round 1 (thread 3 — the catalog criteria before
        // distance pagination): the paged radius form cannot apply the
        // catalog's optional predicates (its ordering and its predicates
        // are realestate-owned), so the criteria-ELIGIBLE ACTIVE set
        // resolves HERE and the radius query pages through the
        // intersection — a category/price/guests criterion now filters the
        // distance-ordered page (previously silently ignored: the review's
        // example, guests=4&sort=distance could return listings that
        // cannot accommodate four guests). The eligible set is ACTIVE-gated
        // by construction, replacing the bare findActiveListingIds() call;
        // a criteria with no catalog predicates keeps that cheaper path.
        Set<UUID> eligibleIds = criteria.hasCatalogCriteria()
                ? catalogSearchPort.findActiveListingIdsMatching(criteria)
                : catalogSearchPort.findActiveListingIds();
        Set<UUID> restrictTo = facetIds != null
                ? eligibleIds.stream().filter(facetIds::contains).collect(Collectors.toSet())
                : eligibleIds;
        if (restrictTo.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no query
        }

        PagedResponse<UUID> matches = providerIds != null
                ? realestatePropertyFilterPort.findWithinRadiusPagedRestricted(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters(),
                        restrictTo, providerIds, SpringPagination.toPagedRequest(pageable))
                : realestatePropertyFilterPort.findWithinRadiusPaged(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters(),
                        restrictTo, SpringPagination.toPagedRequest(pageable));

        if (matches.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, matches.totalElements());
        }
        List<ListingSummary> summaries = catalogSearchPort.findSummariesByIds(matches.content());
        // order already preserved by findSummariesByIds; total from the
        // radius flow's side (the restriction that defines the page)
        return new PageImpl<>(summaries, pageable, matches.totalElements());
    }

    /**
     * CodeRabbit PR #300 round 1 (thread 2): the radius flow's area-sorted
     * page (non-text) — the {@link #searchAreaSorted} composition with the
     * radius restriction joined to the caller-resolved eligible set. The
     * realestate-owned radius paging materializes as the port's own
     * composition: the ST_DWithin listing-id SET (the port's set form —
     * the radius restriction is realestate-owned) intersected with the
     * criteria-ELIGIBLE ACTIVE set (the catalog criteria resolve on the
     * catalog's side, thread 3's discipline — the paged form cannot apply
     * them), and the port's existing paged property form pages through
     * its own {@code areaM2} ordering (the marker's direction honored by
     * the adapter's entity-side mapping — a native query would need baked
     * per-direction variants, doubling the pair for no behavioral gain)
     * restricted to that intersection. The summaries are fetched in that
     * order and the page total is the property side's (the counts cannot
     * lie). The provider restriction rides the paged form's restricted
     * variant (the window path); rows without a DECLARED area stay
     * excluded from the area view (the paged form's own discipline).
     */
    private Page<ListingSummary> searchRadiusAreaSorted(SearchCriteria criteria,
                                                        Set<UUID> providerIds, Pageable pageable) {
        Set<UUID> radiusIds = providerIds != null
                ? realestatePropertyFilterPort.findListingIdsWithinRadiusRestricted(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters(), providerIds)
                : realestatePropertyFilterPort.findListingIdsWithinRadius(
                        criteria.latitude(), criteria.longitude(), criteria.radiusMeters());
        if (radiusIds.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no property query
        }
        // thread 3's discipline applied to the new flow from day one: the
        // catalog criteria resolve on the catalog's side (the paged form's
        // predicates are realestate-owned)
        Set<UUID> eligibleIds = criteria.hasCatalogCriteria()
                ? catalogSearchPort.findActiveListingIdsMatching(criteria)
                : catalogSearchPort.findActiveListingIds();
        Set<UUID> restrictTo = eligibleIds.stream()
                .filter(radiusIds::contains)
                .collect(Collectors.toSet());
        if (restrictTo.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no property query
        }
        Set<UUID> locationIds = criteria.locationId() != null
                ? geoLookupPort.findSelfAndDescendants(criteria.locationId())
                : null;
        PropertyCriteria propertyCriteria = toPropertyCriteria(criteria, locationIds);
        PagedResponse<RealestatePropertyFilterPort.PropertyMatch> matches = providerIds != null
                ? realestatePropertyFilterPort.findMatchingPagedRestricted(
                        propertyCriteria, restrictTo, providerIds, SpringPagination.toPagedRequest(pageable))
                : realestatePropertyFilterPort.findMatchingPaged(
                        propertyCriteria, restrictTo, SpringPagination.toPagedRequest(pageable));
        if (matches.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, matches.totalElements());
        }
        List<UUID> orderedIds = matches.content().stream()
                .map(RealestatePropertyFilterPort.PropertyMatch::listingId)
                .toList();
        List<ListingSummary> summaries = catalogSearchPort.findSummariesByIds(orderedIds);
        // order already preserved by findSummariesByIds; total from the
        // property side (the restriction that defines the page)
        return new PageImpl<>(summaries, pageable, matches.totalElements());
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
            // R6 (Wave 5): the full criteria compose into the restricted
            // text query — the optional catalog predicates ride along with
            // the window restriction.
            return SpringPagination.toPage(catalogSearchPort.searchFullTextRestricted(criteria, availableProviderIds, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        // Covers the price / category / browse-all branches: they are the
        // optional predicates of one criteria query.
        return SpringPagination.toPage(catalogSearchPort.searchByCriteriaRestricted(criteria, availableProviderIds, SpringPagination.toPagedRequest(pageable)), pageable);
    }

    /** The pre-L27 dispatch — byte-identical for windowless criteria. */
    private Page<ListingSummary> searchUnwindowed(SearchCriteria criteria, Pageable pageable) {
        String query = criteria.query();
        String category = criteria.category();
        if (query != null && !query.isBlank()) {
            // R6 (comprehensive-review-ar fix plan §4, Wave 5): the text
            // branch passes the FULL criteria — the optional catalog
            // predicates (category / price bounds / guests) compose with
            // the text predicate inside the same native query and its
            // count, instead of being silently dropped (the review's R6:
            // a q + filters request answered the unfiltered text match
            // set). Raw user input passed through: the official
            // websearch_to_tsquery (ProviderListingRepository) parses it
            // leniently and supports "quoted phrases", OR and -exclusion.
            // The former hand-mangling (replaceAll("\\s+", " & ")) both
            // corrupted the user's phrase intent and fed to_tsquery
            // invalid syntax for quotes/parens/dashes (SQL exception ->
            // HTTP 500).
            return SpringPagination.toPage(catalogSearchPort.searchFullText(criteria, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        if (criteria.minPrice() != null || criteria.maxPrice() != null || criteria.guests() != null
                // W3 (G17, greptile round 1, adopted from the root): a
                // floor-carrying BROWSE request (no price/guests/category —
                // e.g. `GET /search?minRating=4`) must route onto the
                // criteria query, where the catalog resolves the rating-floor
                // set — the legacy listActive/listByCategory reads carry no
                // criteria and would silently bypass the floor. The same
                // routing applies to a category+floor request (category is
                // an optional predicate of the same query).
                || criteria.hasMinRating()) {
            // I6: guests joins price as an optional predicate of the criteria
            // query — a guests-only criterion routes here too (NOT listActive,
            // which would silently bypass the capacity filter).
            return SpringPagination.toPage(catalogSearchPort.searchByCriteria(criteria, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        if (category != null && !category.isBlank()) {
            return SpringPagination.toPage(catalogSearchPort.listByCategory(category, SpringPagination.toPagedRequest(pageable)), pageable);
        }
        return SpringPagination.toPage(catalogSearchPort.listActive(SpringPagination.toPagedRequest(pageable)), pageable);
    }

    // W6 (search-unit compliance pass): the two legacy delegation overloads
    // searchByCategory(category, pageable) and searchAll(pageable) were
    // REMOVED — they had no production caller (the /category endpoint now
    // delegates to the ONE criteria search) and they bypassed the single
    // dispatch: a sorted request through them reached the catalog WITHOUT
    // the controller's normalize() gate (an unmapped sort property then
    // failed deep inside the Specification path as an attribute-lookup
    // error — an HTTP 500 the search surface answers 400 for). The criteria
    // form is the ONE entry into the search-results-v6 cache, keyed
    // exclusively through the generator; every browse form (category-only,
    // empty) routes through it byte-identically (searchUnwindowed's legacy
    // branch calls the same listByCategory/listActive reads).

    /** Builds the resolved property contract from the criteria (gated upstream). */
    private static PropertyCriteria toPropertyCriteria(SearchCriteria criteria, Set<UUID> locationIds) {
        return new PropertyCriteria(
                criteria.purpose(),
                criteria.propertyType(),
                criteria.minRooms(),
                criteria.minBathrooms(),
                criteria.minAreaM2(),
                locationIds);
    }
}

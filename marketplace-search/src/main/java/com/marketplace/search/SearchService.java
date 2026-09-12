package com.marketplace.search;

import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    // The -v3 suffix is the ListingSummary serialization-schema namespace —
    // see CatalogService.CATALOG_CACHE_NAMES: the L32 criteria schema
    // extension bumps it (the plan's D-E6 decision; the key generator's
    // prefix bump l27v2 → l32v1 keeps the key spaces disjoint too — no
    // pre-change entry can be read as a post-change hit; the one-time cold
    // cycle is bounded by the 1h TTL).
    @Cacheable(cacheNames = "search-results-v3", key = "(#query == null ? '' : #query.trim()) + '|' + (#category == null ? '' : #category.trim()) + '|' + #pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public Page<ListingSummary> search(String query, String category, Pageable pageable) {
        // CodeRabbit PR #299 round 1 (normalize ONCE): this legacy entry has
        // no production caller, but its contract is the same as the
        // controller's — the pageable is normalized at the service boundary
        // so every downstream branch consumes one representation.
        return search(new SearchCriteria(query, category, null, null),
                SearchSorts.normalize(pageable));
    }

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
     */
    @Cacheable(cacheNames = "search-results-v3", keyGenerator = "searchCriteriaKeyGenerator")
    public Page<ListingSummary> search(SearchCriteria criteria, Pageable pageable) {
        // CodeRabbit PR #299 round 1 (two findings, one root):
        // (a) the property flow was selected for an IGNORED text-search
        //     sort — a text query with sort=area restricted the full-text
        //     search to property-bearing listings although the documented
        //     behavior is "text searches ignore the sort": the area marker
        //     selects the property flow ONLY for blank queries; property
        //     CRITERIA keep selecting it for text searches (their set
        //     restriction is real filtering, not sorting);
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
            // rides the Specification path — the native criteria query's
            // baked ORDER BY cannot honor a sort (it used to be a SQL
            // error). Windowed searches keep the deterministic id order of
            // the L27 restricted path (documented scope boundary).
            String query = criteria.query();
            if (query == null || query.isBlank()) {
                // already normalized at the controller — consumed as-is
                return catalogSearchPort.searchByCriteriaFaceted(criteria, pageable);
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
                        || "createdAt".equals(order.getProperty()));
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
            return searchAreaSorted(propertyCriteria, providerIds, pageable);
        }

        Set<UUID> listingIds = providerIds != null
                ? realestatePropertyFilterPort.findListingIdsMatchingRestricted(propertyCriteria, providerIds)
                : realestatePropertyFilterPort.findListingIdsMatching(propertyCriteria);
        if (listingIds.isEmpty()) {
            return Page.empty(pageable); // honest empty page, no catalog query
        }

        if (textQuery) {
            return catalogSearchPort.searchFullTextRestrictedToListings(
                    query.trim(), listingIds, pageable);
        }
        return catalogSearchPort.searchByCriteriaRestrictedToListings(
                criteria, listingIds, pageable);
    }

    /**
     * The area-sorted flow (browse/filter searches — text queries rank by
     * relevance and ignore the area sort, documented): the realestate port
     * pages through its own area ordering restricted to the catalog's
     * ACTIVE id set; the summaries are fetched in that order and the page
     * total is the property side's (the counts cannot lie).
     */
    private Page<ListingSummary> searchAreaSorted(PropertyCriteria propertyCriteria,
                                                  Set<UUID> providerIds, Pageable pageable) {
        Set<UUID> activeIds = catalogSearchPort.findActiveListingIds();
        Page<RealestatePropertyFilterPort.PropertyMatch> matches = providerIds != null
                ? realestatePropertyFilterPort.findMatchingPagedRestricted(
                        propertyCriteria, activeIds, providerIds, pageable)
                : realestatePropertyFilterPort.findMatchingPaged(
                        propertyCriteria, activeIds, pageable);

        if (matches.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, matches.getTotalElements());
        }
        List<UUID> orderedIds = matches.getContent().stream()
                .map(RealestatePropertyFilterPort.PropertyMatch::listingId)
                .toList();
        List<ListingSummary> summaries = catalogSearchPort.findSummariesByIds(orderedIds);
        // order already preserved by findSummariesByIds; total from the
        // property side (the restriction that defines the page)
        return new PageImpl<>(summaries, pageable, matches.getTotalElements());
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
        if (criteria.minPrice() != null || criteria.maxPrice() != null || criteria.guests() != null) {
            // I6: guests joins price as an optional predicate of the criteria
            // query — a guests-only criterion routes here too (NOT listActive,
            // which would silently bypass the capacity filter).
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

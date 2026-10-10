package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.PropertyPurpose;
import com.marketplace.shared.api.PropertyType;
import com.marketplace.shared.api.SearchCriteria;
import org.springframework.data.domain.Pageable;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = ApiConstants.SEARCH, version = "1.0")
public class SearchController {

    private final SearchService searchService;
    private final UnifiedSearchService unifiedSearchService;

    public SearchController(SearchService searchService, UnifiedSearchService unifiedSearchService) {
        this.searchService = searchService;
        this.unifiedSearchService = unifiedSearchService;
    }

    @GetMapping
    @RateLimiter(name = "search")
    @Operation(summary = "Search listings",
            description = "Full-text search with typo tolerance (pg_trgm) and optional filters. "
                    + "The price bounds are non-negative and ordered (min <= max when both "
                    + "are present) — an inverted or negative range is a 400 before any query. "
                    + "When both stay-window dates are present, the window must be a valid "
                    + "half-open interval [checkIn, checkOut) and results are restricted to "
                    + "providers with an available slot overlapping it. A guests value, when "
                    + "present, must be positive and restricts results to listings whose "
                    + "declared capacity accommodates it (undeclared-capacity listings never "
                    + "match — I6/roadmap D1)."
                    + " L32: the real-estate facets — location (a geo node: the searched node "
                    + "and all its descendants), purpose (RENT/SALE), propertyType, minRooms, "
                    + "minBathrooms, minAreaM2 — resolve through the realestate filter port; "
                    + "invalid values are rejected before any query."
                    + " P1 (PostGIS): the radius triple — lat/lng/radiusKm (all three together "
                    + "or none; the radius ceiling is 50 km; whole-meter precision) — matches "
                    + "listings with declared coordinates within the radius (ST_DWithin), "
                    + "composing WITH the geo hierarchy and the facets; listings without "
                    + "coordinates never match. "
                    + "Sorting: price/newest "
                    + "apply to filter searches (windowed searches included — the restricted "
                    + "query honors the mapped sort with the id tiebreak), area orders by the "
                    + "declared square meters "
                    + "(listings with an undeclared area are excluded from the area view); "
                    + "text searches always rank by relevance — the sort whitelist is ignored "
                    + "for them. sort=distance (nearest-first, requires the radius triple) "
                    + "orders by the server-side ST_Distance. W3: sort=rating (highest first — "
                    + "the daily-computed composite rating × log(count) × completeness × "
                    + "recency; ascending is not supported) rides the filter flow; minRating "
                    + "(within [1, 5]) filters to providers at or above the stars floor. "
                    + "Unsupported sort properties answer 400.")
    public ResponseEntity<PagedResponse<ListingSummary>> searchWithCriteria(
            @Parameter(description = "Free-text query (websearch syntax: quoted phrases, OR, -exclusions)",
                    example = "\"sea view\" jeddah")
            @RequestParam(required = false) String q,
            @Parameter(description = "Exact category filter", example = "stay")
            @RequestParam(required = false) String category,
            @Parameter(description = "Minimum price filter (major units; non-negative)", example = "150")
            @RequestParam(required = false) java.math.BigDecimal minPrice,
            @Parameter(description = "Maximum price filter (major units; non-negative, at least minPrice "
                    + "when both bounds are present)", example = "800")
            @RequestParam(required = false) java.math.BigDecimal maxPrice,
            // L27: the stay window [checkIn, checkOut) — exclusive end. The
            // SearchCriteria record is the gate: an incomplete (one date
            // without the other), reversed or zero-length window is a 400 at
            // construction (before any query); the half-open interval itself
            // is the valid form and is never rejected.
            @Parameter(description = "Stay window start (inclusive), ISO-8601 instant — must be "
                    + "paired with checkOut", example = "2026-10-01T14:00:00Z")
            @RequestParam(required = false) java.time.Instant checkIn,
            @Parameter(description = "Stay window end (EXCLUSIVE), ISO-8601 instant — must be "
                    + "paired with checkIn", example = "2026-10-04T10:00:00Z")
            @RequestParam(required = false) java.time.Instant checkOut,
            // I6: the guest-capacity criterion — the same record gate rejects
            // non-positive values (400 before any query).
            @Parameter(description = "Guest count the listing must accommodate (positive); "
                    + "listings without declared capacity never match", example = "4")
            @RequestParam(required = false) Integer guests,
            // L32 (realestate systems plan): the real-estate facets — all
            // optional, all type-gated by the SearchCriteria record (invalid
            // values are 400 before any query; an unknown locationId is 404
            // from the geo port, never a silently-empty page).
            @Parameter(description = "Geo location node — matches the node AND all its "
                    + "descendants (unknown id: 404)", example = "11111111-1111-4111-8111-111111111103")
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID locationId,
            @Parameter(description = "Transaction purpose (RENT or SALE)", example = "RENT")
            @org.springframework.web.bind.annotation.RequestParam(required = false) PropertyPurpose purpose,
            @Parameter(description = "Physical property kind (APARTMENT/VILLA/LAND/SHOP/OFFICE/GARAGE)",
                    example = "APARTMENT")
            @org.springframework.web.bind.annotation.RequestParam(required = false) PropertyType propertyType,
            @Parameter(description = "Minimum rooms (positive)", example = "2")
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer minRooms,
            @Parameter(description = "Minimum bathrooms (positive)", example = "1")
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer minBathrooms,
            @Parameter(description = "Minimum area in square meters (positive)", example = "80")
            @org.springframework.web.bind.annotation.RequestParam(required = false) Integer minAreaM2,
            // P1 (postgis integration plan): the radius triple — all
            // optional, all type-gated by the SearchCriteria record (the
            // stay window's group discipline: a partial presence is a 400
            // at construction, before any query; the ranges mirror V48;
            // the radius ceiling is the plan's calibration). The radius
            // composes WITH the geo hierarchy and the facets (AND).
            @Parameter(description = "Radius search center latitude (within [-90, 90]) — must be "
                    + "paired with lng and radiusKm", example = "33.558889")
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal lat,
            @Parameter(description = "Radius search center longitude (within [-180, 180]) — must be "
                    + "paired with lat and radiusKm", example = "36.056944")
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal lng,
            @Parameter(description = "Search radius in kilometers, (0, 50], whole-meter precision — "
                    + "must be paired with lat and lng", example = "10")
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal radiusKm,
            // W3 (yelp-level plan §5 — G17): the min-stars floor
            // («فلتر حد أدنى من النجوم») — type-gated by the SearchCriteria
            // record ([1, 5], a 400 before any query) and composed as the
            // set-restriction pattern inside the catalog flows.
            @Parameter(description = "Minimum provider rating (verified stars), within [1, 5] — "
                    + "listings of providers below the floor never match", example = "4")
            @org.springframework.web.bind.annotation.RequestParam(required = false) java.math.BigDecimal minRating,
            Pageable pageable) {
        SearchCriteria criteria = new SearchCriteria(q, category, minPrice, maxPrice,
                checkIn, checkOut, guests, locationId, purpose, propertyType,
                minRooms, minBathrooms, minAreaM2, lat, lng, radiusKm, minRating);
        // L32: the sort whitelist is normalized HERE (before the cache key —
        // the effective sort rides the key) — unsupported properties are 400.
        Pageable effective = SearchSorts.normalize(pageable);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, effective)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "search")
    @Operation(summary = "Search listings by category",
            description = "Category browse — the convenience form of the main search surface "
                    + "with the category bound from the path. W6 (search-unit compliance pass): "
                    + "the endpoint delegates to the ONE criteria search — the same dispatch, "
                    + "the same sort whitelist and the same cache the main surface applies: "
                    + "price/newest/rating order the category's filter results, area orders by "
                    + "the declared square meters, and an unsupported sort property answers 400 "
                    + "(previously this endpoint bypassed the normalize() gate entirely — any "
                    + "sort parameter failed deep inside the query path as a server error).")
    public ResponseEntity<PagedResponse<ListingSummary>> searchByCategory(
            @PathVariable String category, Pageable pageable) {
        // W6: the SINGLE dispatch — the category rides the criteria record
        // (its criterion-less form routes byte-identically to the legacy
        // category read), and the sort passes the SAME normalize() gate the
        // main surface applies (the single normalization point; the mapped
        // names + the id tiebreak are what the Specification paths consume).
        SearchCriteria criteria = new SearchCriteria(null, category, null, null);
        Pageable effective = SearchSorts.normalize(pageable);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, effective)));
    }

    /**
     * Stage 5 (community platform execution plan — the unified legal
     * multi-domain search): the merged multi-domain answer. Every consulted
     * domain answers through its own {@code UnifiedSearchSourcePort}
     * adapter with its own visibility predicates — this surface orchestrates
     * and owns nothing. REST and the AI tool ride the SAME
     * {@code MarketplaceSearchPort.unified} method (parity by construction;
     * measured by the parity integration test).
     */
    @GetMapping("/unified")
    @RateLimiter(name = "search")
    @Operation(summary = "Unified multi-domain search",
            description = "Stage 5: one query across the community posts, community events, "
                    + "knowledge entries, and the institutions registry — each source answers "
                    + "through its own visibility contract (hidden/withdrawn/rejected rows never "
                    + "appear), merged deterministically (source declaration order, each group in "
                    + "its own relevance order), each source capped at limit. A degraded source "
                    + "degrades to its absence (degradedSources names it) — the answer never "
                    + "fails whole because one domain is down. Every consultation is measured "
                    + "(search.unified.hits / search.unified.consulted).")
    public ResponseEntity<com.marketplace.shared.api.UnifiedSearchResponse> unified(
            @Parameter(description = "Free-text query (required)", example = "سباكة حي السلام")
            @RequestParam String q,
            @Parameter(description = "Geo node to scope every source by (optional — all locations "
                    + "when absent)")
            @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Per-source cap, within [1, 20]", example = "5")
            @RequestParam(defaultValue = "5") int limit) {
        var query = new com.marketplace.shared.api.UnifiedSearchQuery(q, locationId, limit);
        return ResponseEntity.ok(unifiedSearchService.search(query));
    }
}

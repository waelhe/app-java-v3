package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value = ApiConstants.SEARCH, version = "1.0")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @RateLimiter(name = "search")
    @Operation(summary = "Search listings",
            description = "Full-text search with typo tolerance (pg_trgm) and optional filters. "
                    + "When both stay-window dates are present, the window must be a valid "
                    + "half-open interval [checkIn, checkOut) and results are restricted to "
                    + "providers with an available slot overlapping it. A guests value, when "
                    + "present, must be positive and restricts results to listings whose "
                    + "declared capacity accommodates it (undeclared-capacity listings never "
                    + "match — I6/roadmap D1).")
    public ResponseEntity<PagedResponse<ListingSummary>> searchWithCriteria(
            @Parameter(description = "Free-text query (websearch syntax: quoted phrases, OR, -exclusions)",
                    example = "\"sea view\" jeddah")
            @RequestParam(required = false) String q,
            @Parameter(description = "Exact category filter", example = "stay")
            @RequestParam(required = false) String category,
            @Parameter(description = "Minimum price filter (major units)", example = "150")
            @RequestParam(required = false) java.math.BigDecimal minPrice,
            @Parameter(description = "Maximum price filter (major units)", example = "800")
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
            Pageable pageable) {
        SearchCriteria criteria = new SearchCriteria(q, category, minPrice, maxPrice, checkIn, checkOut, guests);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, pageable)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "search")
    @Operation(summary = "Search listings by category",
            description = "Category-restricted search with the same typo tolerance as the main "
                    + "search surface.")
    public ResponseEntity<PagedResponse<ListingSummary>> searchByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(searchService.searchByCategory(category, pageable)));
    }
}

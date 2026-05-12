package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping(value = ApiConstants.SEARCH, version = "1.0")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @RateLimiter(name = "search")
    public ResponseEntity<PagedResponse<ListingSummary>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long minPriceCents,
            @RequestParam(required = false) Long maxPriceCents,
            @RequestParam(required = false) Integer minRating,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant availableFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant availableTo,
            @RequestParam(required = false) String sort,
            Pageable pageable) {
        SearchCriteria criteria = new SearchCriteria(q, category, minPriceCents, maxPriceCents, minRating, location, availableFrom, availableTo, sort);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, pageable)));
    }

    @RateLimiter(name = "search")
    public ResponseEntity<PagedResponse<ListingSummary>> search(String q, String category, Pageable pageable) {
        SearchCriteria criteria = new SearchCriteria(q, category, null, null, null, null, null, null, null);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, pageable)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "search")
    public ResponseEntity<PagedResponse<ListingSummary>> searchByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(searchService.searchByCategory(category, pageable)));
    }
}

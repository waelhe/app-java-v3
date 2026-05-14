package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.SearchCriteria;
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
    public ResponseEntity<PagedResponse<ListingSummary>> searchWithCriteria(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) java.math.BigDecimal minPrice,
            @RequestParam(required = false) java.math.BigDecimal maxPrice,
            Pageable pageable) {
        SearchCriteria criteria = new SearchCriteria(q, category, minPrice, maxPrice);
        return ResponseEntity.ok(PagedResponse.of(searchService.search(criteria, pageable)));
    }

    @RateLimiter(name = "search")
    public ResponseEntity<PagedResponse<ListingSummary>> search(
            String q,
            String category,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(searchService.search(q, category, pageable)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "search")
    public ResponseEntity<PagedResponse<ListingSummary>> searchByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(searchService.searchByCategory(category, pageable)));
    }
}

package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(ApiConstants.SEARCH)
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

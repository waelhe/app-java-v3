package com.marketplace.app.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedRequest;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.UnifiedSearchDomain;
import com.marketplace.shared.api.UnifiedSearchHit;
import com.marketplace.shared.api.UnifiedSearchPort;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The unified search REST surface (§5.1): ONE entry by domain and
 * geography. The domain is the enum whitelist (an unknown name is the clean
 * 400, never a silent fallback); the caller's id resolves through the
 * standing {@link CurrentUserProvider} seam (anonymous callers reach the
 * LISTINGS domain and are rejected by the community leg's own contract).
 *
 * <p>Rate limiting rides the standing {@code search} limiter — the same
 * budget the listings search surface spends.
 */
@RestController
@RequestMapping(value = ApiConstants.SEARCH_UNIFIED, version = "1.0")
public class UnifiedSearchController {

    private final UnifiedSearchPort unifiedSearch;
    private final CurrentUserProvider currentUserProvider;

    public UnifiedSearchController(UnifiedSearchPort unifiedSearch,
                                   CurrentUserProvider currentUserProvider) {
        this.unifiedSearch = unifiedSearch;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping
    @RateLimiter(name = "search")
    @Operation(summary = "The unified search entry",
            description = "One search entry by domain and geography (§5.1). domain=LISTINGS rides the "
                    + "listings search orchestration (text + the geo node; the rich facets stay on the "
                    + "listings surface). domain=COMMUNITY_POSTS text-searches the caller's OWN active "
                    + "neighborhood's visible posts (Arabic FTS primary, pg_trgm fallback, relevance "
                    + "order) and requires an authenticated caller. A location, when supplied, is "
                    + "resolved by the owning domain path — an unknown node is rejected, never silently "
                    + "widened into a national query. Every hit carries its source, time, state, and "
                    + "the original record's id.")
    public ResponseEntity<PagedResponse<UnifiedSearchHit>> search(
            @RequestParam("domain") String domain,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "locationId", required = false) UUID locationId,
            @PageableDefault(size = 20) Pageable pageable,
            Authentication authentication) {
        UnifiedSearchDomain parsed = parseDomain(domain);
        UUID callerId = currentUserProvider.tryGetCurrentUserId(authentication).orElse(null);
        PagedRequest request = PagedRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return ResponseEntity.ok(unifiedSearch.search(callerId, parsed, query, locationId, request));
    }

    private static UnifiedSearchDomain parseDomain(String domain) {
        try {
            return UnifiedSearchDomain.valueOf(domain);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown search domain: " + domain);
        }
    }
}

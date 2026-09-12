package com.marketplace.geo;


import com.marketplace.shared.api.GeoLookupPort.GeoNode;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Public read surface for the administrative hierarchy (realestate systems
 * plan L30): anonymous, like the listing browse surface (the same
 * permitAll pattern in SecurityConfig). The tree is served from the
 * {@code geo-tree} cache; children and suggest are repository reads.
 */
@RestController
@RequestMapping(value = "/api/v1/geo", version = "1.0")
public class GeoController {

    private final GeoService geoService;

    public GeoController(GeoService geoService) {
        this.geoService = geoService;
    }

    @GetMapping("/tree")
    @RateLimiter(name = "catalog")
    @Operation(summary = "The full location tree",
            description = "The whole administrative hierarchy (country → governorate → "
                    + "city → neighborhood) in one cached response — small by design at "
                    + "city scale.")
    public ResponseEntity<GeoNode> tree() {
        return ResponseEntity.ok(geoService.getTree());
    }

    @GetMapping("/{id}/children")
    @RateLimiter(name = "catalog")
    @Operation(summary = "Children of one location",
            description = "Direct children in stable slug order; an unknown parent is 404.")
    public ResponseEntity<List<GeoNode>> children(
            @Parameter(description = "The parent location id", example = "7c9e6679-7425-40de-944b-e07fc1f90ae7")
            @PathVariable UUID id) {
        return ResponseEntity.ok(geoService.getChildren(id));
    }

    /**
     * Prefix autocomplete over nameAr/nameEn/slug. The prefix floor is two
     * characters (trimmed) — a shorter prefix is a 400 before any query
     * (the type-gate philosophy, same as {@code SearchCriteria}); an
     * unknown location id elsewhere is 404, never a silently-empty page.
     */
    @GetMapping("/suggest")
    @RateLimiter(name = "geoSuggest")
    @Operation(summary = "Location autocomplete",
            description = "Prefix search (>= 2 characters) over Arabic and Latin names "
                    + "and slugs, ordered shallow-first. Answers 400 below the floor.")
    public ResponseEntity<List<GeoNode>> suggest(
            @Parameter(description = "Name or slug prefix (at least 2 characters)", example = "قد")
            @RequestParam String q) {
        return ResponseEntity.ok(geoService.suggest(q));
    }
}

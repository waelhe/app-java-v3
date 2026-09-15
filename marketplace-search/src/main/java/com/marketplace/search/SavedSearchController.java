package com.marketplace.search;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — saved searches and alerts): the
 * authenticated /me surface. The caller's user id IS the owner key (the
 * plain {@code /me} seam — no profile indirection); every endpoint is
 * behind the resource-server chain's {@code anyRequest().authenticated()}
 * — no security-config change.
 *
 * <p><b>The criteria in the request body is a raw JSON node</b> — the
 * service materializes it through the SAME {@code SearchCriteria}
 * canonical constructor the search surface binds, so the type gate (400
 * before any write) and the stored semantics are the search's own, by
 * construction.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class SavedSearchController {

    private final SavedSearchService savedSearchService;
    private final CurrentUserProvider currentUserProvider;

    public SavedSearchController(SavedSearchService savedSearchService,
                                 CurrentUserProvider currentUserProvider) {
        this.savedSearchService = savedSearchService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The L29 independent named rate-limiter instance (timeout 0: fail
     * fast with 429 RL-001) — conservative until real traffic.
     */
    @PostMapping("/me/saved-searches")
    @RateLimiter(name = "savedSearchCreate")
    @Operation(summary = "Save a search",
            description = "Stores the caller's search criteria for later re-use and (optionally) "
                    + "instant alerts: a newly activated listing matching the criteria notifies "
                    + "the caller (SAVED_SEARCH_MATCH — in-app row always, WebSocket/email per "
                    + "the standing per-type/channel preferences). The criteria object is the "
                    + "search surface's own: the same validation answers 400 here (invalid "
                    + "window, non-positive guests/rooms, partial radius triple, unknown enum "
                    + "name); an unknown location answers 404. alertEnabled=false stores the "
                    + "search without alerts.")
    public ResponseEntity<SavedSearchView> create(
            @Valid @RequestBody SavedSearchCreateRequest request,
            Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        SavedSearch saved = savedSearchService.create(userId, request.criteria(), request.alertEnabled());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(SavedSearchView.of(saved, request.criteria()));
    }

    @GetMapping("/me/saved-searches")
    @Operation(summary = "List my saved searches",
            description = "The caller's saved searches, newest first (deterministic order).")
    public ResponseEntity<PagedResponse<SavedSearchView>> list(
            Pageable pageable, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(
                savedSearchService.listFor(userId, pageable).map(s -> SavedSearchView.of(s, null))));
    }

    @DeleteMapping("/me/saved-searches/{id}")
    @Operation(summary = "Delete a saved search",
            description = "Soft-deletes the caller's saved search. A foreign id is a 404 — it is "
                    + "not in your list.")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id, Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        savedSearchService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * The create body: the criteria as a raw JSON node (materialized and
     * gated by the service through the SearchCriteria record) plus the
     * alert flag.
     */
    public record SavedSearchCreateRequest(
            @NotNull
            @Schema(description = "The search criteria object — the search surface's own shape "
                    + "(q, category, minPrice, maxPrice, checkIn/checkOut, guests, locationId, "
                    + "purpose, propertyType, minRooms, minBathrooms, minAreaM2, "
                    + "latitude/longitude/radiusKm).")
            JsonNode criteria,
            @Schema(description = "FALSE stores the search for later re-use only — no alerts.")
            boolean alertEnabled
    ) {
    }

    /**
     * The read model: the stored criteria echoed verbatim (the create
     * response echoes the request node; the list re-serializes the stored
     * record through the app mapper — the same round-trip the matcher
     * trusts).
     */
    public record SavedSearchView(
            UUID id,
            JsonNode criteria,
            boolean alertEnabled,
            Instant lastMatchedAt,
            Instant createdAt
    ) {
        static SavedSearchView of(SavedSearch saved, JsonNode requestCriteria) {
            return new SavedSearchView(
                    saved.getId(),
                    requestCriteria != null ? requestCriteria : SavedSearchViews.criteriaNode(saved),
                    saved.isAlertEnabled(),
                    saved.getLastMatchedAt(),
                    saved.getCreatedAt());
        }
    }
}

package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * W3 (yelp-level-plan §5 — G19): the member's saved-listings surface —
 * «حفظ لاحقًا» from the results page and «قائمتي» as the list. The
 * member's OWN relation (the SavedSearch controller's own home shape:
 * {@code /api/v1/me/...} — the caller is the scope, there is no
 * user parameter to forge).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ListingFavoritesController {

    private final ListingFavoritesService favoritesService;
    private final CurrentUserProvider currentUserProvider;

    public ListingFavoritesController(ListingFavoritesService favoritesService,
                                      CurrentUserProvider currentUserProvider) {
        this.favoritesService = favoritesService;
        this.currentUserProvider = currentUserProvider;
    }

    @GetMapping("/me/favorites")
    @Operation(summary = "My saved listings",
            description = "The caller's live favorites, newest-saved first — each row carries "
                    + "the listing's CURRENT status (a saved listing that later expires or is "
                    + "withdrawn by its provider stays saved and shows the truth). Deterministic "
                    + "pagination on the complete (savedAt, id) key.")
    public ResponseEntity<Page<ListingFavoritesService.ListingFavoriteView>> myFavorites(
            @Parameter(description = "The page request; the sort is forced to newest-saved first")
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            Authentication authentication) {
        return ResponseEntity.ok(
                favoritesService.myFavorites(currentUserProvider.getCurrentUserId(authentication), pageable));
    }

    @PostMapping("/me/favorites/{listingId}")
    @Operation(summary = "Save a listing for later",
            description = "W3 (G19): the member's «حفظ لاحقًا». Unknown listing answers 404 "
                    + "before any write; an already-saved live pair answers 409 (the unique "
                    + "key's own contract).")
    public ResponseEntity<ListingFavoritesService.ListingFavoriteView> save(
            @PathVariable UUID listingId,
            Authentication authentication) {
        return ResponseEntity.status(201)
                .body(favoritesService.save(
                        currentUserProvider.getCurrentUserId(authentication), listingId));
    }

    @DeleteMapping("/me/favorites/{listingId}")
    @Operation(summary = "Withdraw a saved listing",
            description = "The house soft delete: the row stays for the audit trail, the reads "
                    + "stop returning it. A pair with no live favorite answers the honest 404.")
    public ResponseEntity<Void> unsave(
            @PathVariable UUID listingId,
            Authentication authentication) {
        favoritesService.unsave(currentUserProvider.getCurrentUserId(authentication), listingId);
        return ResponseEntity.noContent().build();
    }
}

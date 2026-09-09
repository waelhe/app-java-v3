package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.IsoCurrencyCode;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.CATALOG, version = "1.0")
public class CatalogController {

    private final CatalogService catalogService;
    private final CurrentUserProvider currentUserProvider;
    private final ListingMapper listingMapper;

    public CatalogController(CatalogService catalogService, CurrentUserProvider currentUserProvider, ListingMapper listingMapper) {
        this.catalogService = catalogService;
        this.currentUserProvider = currentUserProvider;
        this.listingMapper = listingMapper;
    }

    @GetMapping
    @RateLimiter(name = "catalog")
    @Operation(summary = "Browse active listings", description = "Paginated ACTIVE listings — the "
            + "public browse surface (L29 mobile docs).")
    public ResponseEntity<PagedResponse<ListingSummary>> listActive(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listActive(pageable)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "catalog")
    @Operation(summary = "Browse active listings by category",
            description = "Paginated ACTIVE listings filtered to one category.")
    public ResponseEntity<PagedResponse<ListingSummary>> listByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByCategory(category, pageable)));
    }

    @GetMapping("/provider/{providerId}")
    @Operation(summary = "Browse one provider's active listings",
            description = "Paginated ACTIVE listings of a provider (public provider profile surface).")
    public ResponseEntity<PagedResponse<ListingResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByProvider(providerId, pageable).map(listingMapper::toResponse)));
    }

    @GetMapping("/{id}")
    @RateLimiter(name = "catalog")
    @Operation(summary = "Get one active listing", description = "The public listing detail — "
            + "INACTIVE/ARCHIVED listings answer 404 on this surface.")
    public ResponseEntity<ListingResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.getActiveById(id)));
    }

    @PostMapping
    @Operation(summary = "Create a listing (provider)", description = "Publishes a new service "
            + "listing with a base nightly price in minor units and an optional guest capacity.")
    public ResponseEntity<ListingResponse> create(@Valid @RequestBody CreateListingRequest request,
                                                  Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        ProviderListingView listing = catalogService.create(
                providerId, request.title(), request.description(),
                request.category(), request.priceCents(), request.currency(),
                request.maxGuests());
        return ResponseEntity.status(HttpStatus.CREATED).body(listingMapper.toResponse(listing));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update my listing (provider)", description = "Owner-scoped update of "
            + "title/description/category/base price; the stored currency is kept when omitted, "
            + "and the stored guest capacity is kept when omitted (the currency contract).")
    public ResponseEntity<ListingResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateListingRequest request,
                                                  Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.update(
                id, request.title(), request.description(),
                request.category(), request.priceCents(), request.currency(),
                request.maxGuests(), authentication)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a listing (provider)", description = "Moves a paused listing "
            + "back to ACTIVE — visible on the public browse surface.")
    public ResponseEntity<ListingResponse> activate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.activate(id, authentication)));
    }

    @PostMapping("/{id}/pause")
    @Operation(summary = "Pause a listing (provider)", description = "Hides the listing from "
            + "the public surface without archiving it.")
    public ResponseEntity<ListingResponse> pause(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.pause(id, authentication)));
    }

    @PostMapping("/{id}/archive")
    @Operation(summary = "Archive a listing (provider)", description = "Retires the listing — "
            + "it leaves the public surface permanently (soft delete).")
    public ResponseEntity<ListingResponse> archive(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.archive(id, authentication)));
    }

    /**
     * Optional ISO 4217 currency of the listing price (roadmap B4). Blank
     * or omitted keeps the house default SAR — existing clients keep the
     * exact previous contract.
     */
    @Schema(description = "Listing creation: title, category, base nightly price in minor units; "
            + "optional ISO 4217 currency (SAR default); optional guest capacity (searchable)")
    public record CreateListingRequest(
            @Schema(description = "Listing title", example = "Sea-view studio in Jeddah")
            @NotBlank String title,
            @Schema(description = "Optional description", example = "Two guests, king bed, "
                    + "five minutes from the corniche.")
            String description,
            @Schema(description = "Listing category", example = "stay")
            @NotBlank String category,
            @Schema(description = "Base price per night in minor units (halalas for SAR)",
                    example = "35000")
            @NotNull Long priceCents,
            @Schema(description = "ISO 4217 currency of the price (optional, SAR default)",
                    example = "SAR")
            @IsoCurrencyCode String currency,
            @Schema(description = "Maximum guests the listing accommodates (optional, "
                    + "positive; undeclared when omitted — I6/roadmap D1)", example = "4")
            @Positive Integer maxGuests
    ) {
    }

    /**
     * Optional ISO 4217 currency: blank/omitted keeps the stored currency
     * (an update that omits the field does not reset money semantics).
     */
    @Schema(description = "Listing update: the same creation shape; an omitted currency keeps "
            + "the stored one, an omitted maxGuests keeps the stored capacity")
    public record UpdateListingRequest(
            @Schema(description = "Listing title", example = "Sea-view studio in Jeddah (renovated)")
            @NotBlank String title,
            @Schema(description = "Optional description", example = "Renovated bathroom, new "
                    + "photos in the gallery.")
            String description,
            @Schema(description = "Listing category", example = "stay")
            @NotBlank String category,
            @Schema(description = "Base price per night in minor units", example = "39000")
            @NotNull Long priceCents,
            @Schema(description = "ISO 4217 currency of the price (optional — keeps stored)",
                    example = "SAR")
            @IsoCurrencyCode String currency,
            @Schema(description = "Maximum guests (optional, positive — keeps stored when "
                    + "omitted; I6/roadmap D1)", example = "6")
            @Positive Integer maxGuests
    ) {
    }
}

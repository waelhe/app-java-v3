package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.IsoCurrencyCode;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
    public ResponseEntity<PagedResponse<ListingSummary>> listActive(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listActive(pageable)));
    }

    @GetMapping("/category/{category}")
    @RateLimiter(name = "catalog")
    public ResponseEntity<PagedResponse<ListingSummary>> listByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByCategory(category, pageable)));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<ListingResponse>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByProvider(providerId, pageable).map(listingMapper::toResponse)));
    }

    @GetMapping("/{id}")
    @RateLimiter(name = "catalog")
    public ResponseEntity<ListingResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.getActiveById(id)));
    }

    @PostMapping
    public ResponseEntity<ListingResponse> create(@Valid @RequestBody CreateListingRequest request,
                                                  Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        ProviderListingView listing = catalogService.create(
                providerId, request.title(), request.description(),
                request.category(), request.priceCents(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(listingMapper.toResponse(listing));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ListingResponse> update(@PathVariable UUID id,
                                                  @Valid @RequestBody UpdateListingRequest request,
                                                  Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.update(
                id, request.title(), request.description(),
                request.category(), request.priceCents(), request.currency(), authentication)));
    }

    @PostMapping("/{id}/activate")
    public ResponseEntity<ListingResponse> activate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.activate(id, authentication)));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<ListingResponse> pause(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.pause(id, authentication)));
    }

    @PostMapping("/{id}/archive")
    public ResponseEntity<ListingResponse> archive(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(listingMapper.toResponse(catalogService.archive(id, authentication)));
    }

    /**
     * Optional ISO 4217 currency of the listing price (roadmap B4). Blank
     * or omitted keeps the house default SAR — existing clients keep the
     * exact previous contract.
     */
    public record CreateListingRequest(
            @NotBlank String title,
            String description,
            @NotBlank String category,
            @NotNull Long priceCents,
            @IsoCurrencyCode String currency
    ) {
    }

    /**
     * Optional ISO 4217 currency: blank/omitted keeps the stored currency
     * (an update that omits the field does not reset money semantics).
     */
    public record UpdateListingRequest(
            @NotBlank String title,
            String description,
            @NotBlank String category,
            @NotNull Long priceCents,
            @IsoCurrencyCode String currency
    ) {
    }
}

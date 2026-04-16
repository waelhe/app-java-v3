package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.CATALOG)
public class CatalogController {

    private final CatalogService catalogService;
    private final CurrentUserProvider currentUserProvider;

    public CatalogController(CatalogService catalogService, CurrentUserProvider currentUserProvider) {
        this.catalogService = catalogService;
        this.currentUserProvider = currentUserProvider;
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
    public ResponseEntity<PagedResponse<ProviderListing>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByProvider(providerId, pageable)));
    }

    @GetMapping("/{id}")
    @RateLimiter(name = "catalog")
    public ResponseEntity<ProviderListing> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> create(@Valid @RequestBody CreateListingRequest request,
                                                    Authentication authentication) {
        UUID providerId = currentUserProvider.getCurrentUserId(authentication);
        ProviderListing listing = catalogService.create(
                providerId, request.title(), request.description(),
                request.category(), request.priceCents());
        return ResponseEntity.status(HttpStatus.CREATED).body(listing);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> update(@PathVariable UUID id,
                                                   @Valid @RequestBody UpdateListingRequest request,
                                                   Authentication authentication) {
        return ResponseEntity.ok(catalogService.update(
                id, request.title(), request.description(),
                request.category(), request.priceCents(), authentication));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> activate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogService.activate(id, authentication));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> pause(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogService.pause(id, authentication));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> archive(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(catalogService.archive(id, authentication));
    }

    public record CreateListingRequest(
            @NotBlank String title,
            String description,
            @NotBlank String category,
            @NotNull Long priceCents
    ) {}

    public record UpdateListingRequest(
            @NotBlank String title,
            String description,
            @NotBlank String category,
            @NotNull Long priceCents
    ) {}
}

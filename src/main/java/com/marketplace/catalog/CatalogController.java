package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(ApiConstants.CATALOG)
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping
    public ResponseEntity<PagedResponse<ProviderListing>> listActive(Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listActive(pageable)));
    }

    @GetMapping("/category/{category}")
    public ResponseEntity<PagedResponse<ProviderListing>> listByCategory(
            @PathVariable String category, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByCategory(category, pageable)));
    }

    @GetMapping("/provider/{providerId}")
    public ResponseEntity<PagedResponse<ProviderListing>> listByProvider(
            @PathVariable UUID providerId, Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(catalogService.listByProvider(providerId, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProviderListing> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.getById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> create(@RequestBody CreateListingRequest request) {
        ProviderListing listing = catalogService.create(
                request.providerId(), request.title(), request.description(),
                request.category(), request.priceCents());
        return ResponseEntity.status(HttpStatus.CREATED).body(listing);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> update(@PathVariable UUID id,
                                                   @RequestBody UpdateListingRequest request) {
        return ResponseEntity.ok(catalogService.update(
                id, request.title(), request.description(),
                request.category(), request.priceCents()));
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> activate(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.activate(id));
    }

    @PostMapping("/{id}/pause")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> pause(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.pause(id));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('PROVIDER')")
    public ResponseEntity<ProviderListing> archive(@PathVariable UUID id) {
        return ResponseEntity.ok(catalogService.archive(id));
    }

    public record CreateListingRequest(
            @NotNull UUID providerId,
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
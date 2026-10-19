package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/** HTTP contracts for the reusable ProductType dictionary used by goods configuration. */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProductTypeController {
    private final ProductTypeService service;
    public ProductTypeController(ProductTypeService service) { this.service = service; }

    @GetMapping("/store/product-types")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List product types available for goods configuration",
            description = "ProductType is distinct from StoreCategory; tourism bookings, property and jobs are not retail product types.")
    public ResponseEntity<List<ProductTypeResponse>> listForConfiguration() {
        return ResponseEntity.ok(service.listForConfiguration().stream().map(ProductTypeResponse::of).toList());
    }

    @GetMapping("/admin/store/product-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read a product type (administrative)")
    public ResponseEntity<ProductTypeResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ProductTypeResponse.of(service.get(id)));
    }

    @PostMapping("/admin/store/product-types")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a reusable goods product type")
    public ResponseEntity<ProductTypeResponse> register(@Valid @RequestBody RegisterProductTypeRequest request) {
        ProductType created = service.register(request.code(), request.nameEn(), request.nameAr(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductTypeResponse.of(created));
    }

    @PutMapping("/admin/store/product-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update product-type display metadata; code is immutable")
    public ResponseEntity<ProductTypeResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateProductTypeRequest request) {
        return ResponseEntity.ok(ProductTypeResponse.of(service.update(id, request.nameEn(), request.nameAr(), request.description())));
    }

    @DeleteMapping("/admin/store/product-types/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Withdraw a product type; retain its audit trail")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }

    public record RegisterProductTypeRequest(
            @Schema(description = "Stable lowercase slug", example = "home-appliances")
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]*$") @Size(max = 50) String code,
            @Schema(description = "English display label", example = "Home appliances", nullable = true) @Size(max = 100) String nameEn,
            @Schema(description = "Arabic display label", example = "الأجهزة المنزلية", nullable = true) @Size(max = 100) String nameAr,
            @Schema(description = "Optional description", nullable = true) @Size(max = 1000) String description) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    public record UpdateProductTypeRequest(
            @Schema(description = "English display label", nullable = true) @Size(max = 100) String nameEn,
            @Schema(description = "Arabic display label", nullable = true) @Size(max = 100) String nameAr,
            @Schema(description = "Optional description", nullable = true) @Size(max = 1000) String description) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    public record ProductTypeResponse(UUID id, String code, String nameEn, String nameAr, String description) {
        static ProductTypeResponse of(ProductType type) {
            return new ProductTypeResponse(type.getId(), type.getCode(), type.getNameEn(), type.getNameAr(), type.getDescription());
        }
    }
}

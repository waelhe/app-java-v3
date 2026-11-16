package com.marketplace.catalog;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.UUID;

/**
 * HTTP contracts for the store-categories dictionary — the A-17/C.7
 * data-operation surface ({@link ProductTypeController} verbatim in shape:
 * same route family, same role gates, same request/response records). The
 * dictionary itself landed with V116; this controller is the missing
 * operation path its own contract names.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class StoreCategoryController {
    private final StoreCategoryService service;
    public StoreCategoryController(StoreCategoryService service) { this.service = service; }

    @GetMapping("/store/categories")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List store categories available for product registration",
            description = "The store vocabulary dictionary in display order; product registration references a row by its stable code.")
    public ResponseEntity<List<StoreCategoryResponse>> listForConfiguration() {
        return ResponseEntity.ok(service.listForConfiguration().stream().map(StoreCategoryResponse::of).toList());
    }

    @GetMapping("/admin/store/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Read a store category (administrative)")
    public ResponseEntity<StoreCategoryResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(StoreCategoryResponse.of(service.get(id)));
    }

    @PostMapping("/admin/store/categories")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a store category dictionary row")
    public ResponseEntity<StoreCategoryResponse> register(@Valid @RequestBody RegisterStoreCategoryRequest request) {
        StoreCategory created = service.register(request.code(), request.nameEn(), request.nameAr(), request.position());
        return ResponseEntity.status(HttpStatus.CREATED).body(StoreCategoryResponse.of(created));
    }

    @PutMapping("/admin/store/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update store-category display metadata and position; code is immutable")
    public ResponseEntity<StoreCategoryResponse> update(@PathVariable UUID id,
                                                        @Valid @RequestBody UpdateStoreCategoryRequest request) {
        return ResponseEntity.ok(StoreCategoryResponse.of(
                service.update(id, request.nameEn(), request.nameAr(), request.position())));
    }

    @DeleteMapping("/admin/store/categories/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Withdraw a store category; the code stays reserved and the audit trail is retained")
    public ResponseEntity<Void> remove(@PathVariable UUID id) {
        service.remove(id);
        return ResponseEntity.noContent().build();
    }

    public record RegisterStoreCategoryRequest(
            @Schema(description = "Stable lowercase slug (the products FK references it)", example = "home-essentials")
            @NotBlank @Pattern(regexp = "^[a-z0-9-]+$", message = "code must be lowercase latin/digits/dashes")
            @Size(max = 50) String code,
            @Schema(description = "English display label", example = "Home essentials", nullable = true) @Size(max = 100) String nameEn,
            @Schema(description = "Arabic display label", example = "أساسيات المنزل", nullable = true) @Size(max = 100) String nameAr,
            @Schema(description = "Storefront display order (ascending); defaults to 0", nullable = true,
                    example = "10") @PositiveOrZero Integer position) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    public record UpdateStoreCategoryRequest(
            @Schema(description = "English display label", nullable = true) @Size(max = 100) String nameEn,
            @Schema(description = "Arabic display label", nullable = true) @Size(max = 100) String nameAr,
            @Schema(description = "Storefront display order (ascending); omitted keeps the stored position",
                    nullable = true, example = "10") @PositiveOrZero Integer position) {
        @AssertTrue(message = "At least one of nameEn or nameAr is required")
        public boolean isDisplayNamePresent() { return present(nameEn) || present(nameAr); }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }

    public record StoreCategoryResponse(UUID id, String code, String nameEn, String nameAr, Integer position) {
        static StoreCategoryResponse of(StoreCategory category) {
            return new StoreCategoryResponse(category.getId(), category.getCode(),
                    category.getNameEn(), category.getNameAr(), category.getPosition());
        }
    }
}

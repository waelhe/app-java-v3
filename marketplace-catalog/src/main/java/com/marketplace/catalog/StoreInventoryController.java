package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 6 (ADR-0002): the store's inventory and lifecycle surface — the
 * provider's own shelf writes. The ownership gate lives on the service
 * (the listing flow's exact shape); the role scope rides the house
 * method-security shape.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/store/products", version = "1.0")
public class StoreInventoryController {

    private final ProductInventoryService inventoryService;

    public StoreInventoryController(ProductInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    /** The provider's shelf write — the absolute stock count. */
    @PutMapping("/{id}/inventory")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Set the product's shelf stock",
            description = "An absolute stock write. Lowering below the reserved set "
                    + "(open placements) answers 409 — the invariant the V172 CHECK "
                    + "pins at the database level.")
    public ResponseEntity<ProductController.ProductResponse> setStock(@PathVariable UUID id,
                                                    @Valid @RequestBody SetStockRequest request,
                                                    Authentication authentication) {
        return ResponseEntity.ok(ProductController.ProductResponse.of(
                inventoryService.setStock(id, request.stockQuantity(), authentication)));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Suspend the product (buyer-invisible)")
    public ResponseEntity<ProductController.ProductResponse> suspend(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(ProductController.ProductResponse.of(
                inventoryService.suspend(id, authentication)));
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Reactivate a suspended product")
    public ResponseEntity<ProductController.ProductResponse> reactivate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(ProductController.ProductResponse.of(
                inventoryService.reactivate(id, authentication)));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Archive the product (terminal)")
    public ResponseEntity<ProductController.ProductResponse> archive(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(ProductController.ProductResponse.of(
                inventoryService.archive(id, authentication)));
    }

    public record SetStockRequest(
            @Schema(description = "The absolute shelf count", example = "12")
            @NotNull @Min(0) Integer stockQuantity) {
    }
}

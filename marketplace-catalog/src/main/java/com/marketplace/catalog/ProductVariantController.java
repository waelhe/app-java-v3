package com.marketplace.catalog;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0012 (the merchandise model's variant depth): the variant's surface
 * — the {@code ProductController}/{@code StoreInventoryController} shapes
 * verbatim, composed for the purchasable unit. The ownership gates live
 * on the service (the caller IS the owning product's provider, or an
 * ADMIN); the reads are the owner's own — the public storefront surfaces
 * arrive with the cart-integration wave.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/store", version = "1.0")
public class ProductVariantController {

    private final ProductVariantService variantService;

    public ProductVariantController(ProductVariantService variantService) {
        this.variantService = variantService;
    }

    /**
     * Registers one variant under an owned product — the seller's write
     * path. The provider and currency are the service's own copy from
     * the owning product; the SKU must be free in the seller's namespace
     * (the house 409 names the collision).
     */
    @PostMapping("/products/{productId}/variants")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Register a variant under one's own product (ADR-0012)",
            description = "Registers one purchasable variant under the owning provider's "
                    + "product. The SKU must be unique within the seller's own namespace "
                    + "(a collision answers 409 with the existing variant named); the "
                    + "currency is the product's own denomination; the stock starts at "
                    + "zero and arrives through the inventory surface.")
    public ResponseEntity<VariantResponse> register(@PathVariable UUID productId,
                                                    @Valid @RequestBody RegisterVariantRequest request,
                                                    Authentication authentication) {
        ProductVariant variant = variantService.register(productId,
                new ProductVariantService.RegisterVariantCommand(
                        request.sku(), request.name(), request.priceMinor(), request.attributeValues()), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(VariantResponse.of(variant));
    }

    /** The owner's roster read — the product's live variants. */
    @GetMapping("/products/{productId}/variants")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "List one's own product's variants (ADR-0012)",
            description = "The owning provider's roster of the product's live variants, "
                    + "SKU-ascending (the index's own order).")
    public ResponseEntity<List<VariantResponse>> listByProduct(@PathVariable UUID productId,
                                                               Authentication authentication) {
        return ResponseEntity.ok(variantService.listOwnedByProduct(productId, authentication)
                .stream().map(VariantResponse::of).toList());
    }

    /** Read back the persisted typed values for an owned variant. */
    @GetMapping("/variants/{id}/attributes")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Read a variant's typed attribute values")
    public ResponseEntity<List<CatalogAttributeValueInput>> listAttributes(@PathVariable UUID id,
            Authentication authentication) {
        return ResponseEntity.ok(variantService.listOwnedAttributeValues(id, authentication));
    }

    /** The owner's read — a stranger's read answers the honest 404. */
    @GetMapping("/variants/{id}")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Read one's own variant (ADR-0012)")
    public ResponseEntity<VariantResponse> getOwned(@PathVariable UUID id,
                                                    Authentication authentication) {
        return ResponseEntity.ok(VariantResponse.of(
                variantService.getOwnedVariant(id, authentication)));
    }

    /** The seller's shelf write — the absolute stock count. */
    @PutMapping("/variants/{id}/inventory")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Set the variant's shelf stock",
            description = "An absolute stock write. Lowering below the reserved set "
                    + "(open placements) answers 409 — the invariant the V219 CHECK "
                    + "pins at the database level.")
    public ResponseEntity<VariantResponse> setStock(@PathVariable UUID id,
                                                    @Valid @RequestBody SetVariantStockRequest request,
                                                    Authentication authentication) {
        return ResponseEntity.ok(VariantResponse.of(
                variantService.setStock(id, request.stockQuantity(), authentication)));
    }

    @PostMapping("/variants/{id}/suspend")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Suspend the variant (buyer-invisible)")
    public ResponseEntity<VariantResponse> suspend(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(VariantResponse.of(
                variantService.suspend(id, authentication)));
    }

    @PostMapping("/variants/{id}/reactivate")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Reactivate a suspended variant")
    public ResponseEntity<VariantResponse> reactivate(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(VariantResponse.of(
                variantService.reactivate(id, authentication)));
    }

    @PostMapping("/variants/{id}/archive")
    @PreAuthorize("hasRole('PROVIDER') or hasRole('ADMIN')")
    @Operation(summary = "Archive the variant (terminal)")
    public ResponseEntity<VariantResponse> archive(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.OK).body(VariantResponse.of(
                variantService.archive(id, authentication)));
    }

    /** The registration contract — the ADR's field set with the house validation shapes. */
    public record RegisterVariantRequest(
            @Schema(description = "The seller's own SKU (unique within the seller's namespace)",
                    example = "ESPRESSO-2CUP-RED")
            @NotBlank @Size(min = 1, max = 100) String sku,
            @Schema(description = "The option's display name", example = "Large / Red",
                    nullable = true)
            @Size(max = 200) String name,
            @Schema(description = "The variant's authoritative unit price in minor units",
                    example = "149900")
            @NotNull @Min(0) Long priceMinor,
            @Schema(description = "Typed VARIANT and VARIANT_SELECTION values. Selection options must form a unique combination for this product.", nullable = true)
            List<CatalogAttributeValueInput> attributeValues
    ) {
        public RegisterVariantRequest {
            attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
        }
        public RegisterVariantRequest(String sku, String name, Long priceMinor) {
            this(sku, name, priceMinor, List.of());
        }
    }

    /** The shelf write's contract (the {@code SetStockRequest} twin). */
    public record SetVariantStockRequest(
            @Schema(description = "The absolute shelf count", example = "12")
            @NotNull @Min(0) Integer stockQuantity
    ) {
    }

    /** The variant's response shape — the same fields the entity declares. */
    public record VariantResponse(
            UUID id,
            UUID productId,
            String sku,
            String name,
            long priceMinor,
            String currency,
            UUID providerId,
            int stockQuantity,
            int reservedQuantity,
            ProductStatus status,
            String optionCombinationKey
    ) {
        public VariantResponse(UUID id, UUID productId, String sku, String name, long priceMinor,
                               String currency, UUID providerId, int stockQuantity,
                               int reservedQuantity, ProductStatus status) {
            this(id, productId, sku, name, priceMinor, currency, providerId, stockQuantity,
                    reservedQuantity, status, null);
        }
        static VariantResponse of(ProductVariant variant) {
            return new VariantResponse(variant.getId(), variant.getProductId(), variant.getSku(),
                    variant.getName(), variant.getPriceMinor(), variant.getCurrency(),
                    variant.getProviderId(), variant.getStockQuantity(),
                    variant.getReservedQuantity(), variant.getStatus(), variant.getOptionCombinationKey());
        }
    }
}

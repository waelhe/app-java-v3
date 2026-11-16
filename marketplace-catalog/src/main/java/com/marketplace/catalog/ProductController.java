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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A-17 (compliance plan C.7 — the M1 store root): the store namespace's
 * first surface — the provider's product registration and the owner's
 * read. The public storefront (browsing, search faces, the Q&amp;A and
 * seller-summary surfaces) arrives with the M2 wave (C.8); M1 is the root
 * the orders line's cart references and the media line's third target
 * attaches photos to.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/store", version = "1.0")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Registers one product — the store root's M1 write path (PROVIDER
     * scope + the dictionary-membership gate live on the service, the
     * house method-security shape).
     */
    @PostMapping("/products")
    @Operation(summary = "Register a store product (M1)",
            description = "Registers one product under the store categories dictionary "
                    + "(the data-managed vocabulary — an unknown category code answers "
                    + "404 before any write). The price fields are the store's own "
                    + "authoritative record; the owning provider is the caller.")
    public ResponseEntity<ProductResponse> register(
            @Valid @RequestBody RegisterProductRequest request, Authentication authentication) {
        Product product = productService.register(new ProductService.RegisterProductCommand(
                request.storeCategoryCode(), request.title(), request.description(),
                request.priceMinor(), request.currency(), request.productTypeId(), request.attributeValues()), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.of(product));
    }

    /** List the current provider's products, including drafts and archived records. */
    @GetMapping("/products")
    @Operation(summary = "List one's own store products")
    public ResponseEntity<List<ProductResponse>> listOwned(Authentication authentication) {
        return ResponseEntity.ok(productService.listOwnedProducts(authentication).stream()
                .map(ProductResponse::of).toList());
    }

    /** Edit descriptive/pricing fields without changing existing order snapshots. */
    @PatchMapping("/products/{id}")
    @Operation(summary = "Edit one's own product")
    public ResponseEntity<ProductResponse> update(@PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request, Authentication authentication) {
        Product updated = productService.updateOwnedProduct(id,
                new ProductService.UpdateProductCommand(request.title(), request.description(), request.priceMinor()),
                authentication);
        return ResponseEntity.ok(ProductResponse.of(updated));
    }

    /** C1 typed goods are created as drafts and require an explicit publication command. */
    @PostMapping("/products/{id}/publish")
    @Operation(summary = "Publish a typed product draft")
    public ResponseEntity<ProductResponse> publish(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(ProductResponse.of(productService.publishOwnedProduct(id, authentication)));
    }

    /** Read back the source-owned typed attributes persisted for this product. */
    @GetMapping("/products/{id}/attributes")
    @Operation(summary = "Read one's own product attribute values")
    public ResponseEntity<List<CatalogAttributeValueInput>> listAttributes(@PathVariable UUID id,
            Authentication authentication) {
        return ResponseEntity.ok(productService.listOwnedAttributeValues(id, authentication));
    }

    /** Replace the product-scope value set, subject to the ProductType's required-field rules. */
    @PutMapping("/products/{id}/attributes")
    @Operation(summary = "Replace one's own product attribute values")
    public ResponseEntity<List<CatalogAttributeValueInput>> replaceAttributes(@PathVariable UUID id,
            @RequestBody List<CatalogAttributeValueInput> values, Authentication authentication) {
        return ResponseEntity.ok(productService.replaceOwnedAttributeValues(id, values, authentication));
    }

    /**
     * The owner's read — the M1 root has no public storefront surface (M2's
     * C.8 wave); a stranger's read answers the honest 404.
     */
    @GetMapping("/products/{id}")
    @Operation(summary = "Read one's own store product (M1)",
            description = "The owning provider's read of their registered product. "
                    + "The public storefront surfaces arrive with the M2 wave.")
    public ResponseEntity<ProductResponse> getOwned(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(ProductResponse.of(
                productService.getOwnedProduct(id, authentication)));
    }

    public record UpdateProductRequest(
            @Schema(description = "The product's display title", example = "Espresso machine, revised")
            @NotBlank @Size(max = 200) String title,
            @Schema(description = "The product description", nullable = true) @Size(max = 2000) String description,
            @NotNull @Min(0) Long priceMinor) { }

    /** The registration contract — the M1 field set with the house validation shapes. */
    public record RegisterProductRequest(
            @Schema(description = "The store category's stable code (the dictionary key)",
                    example = "home-appliances")
            @NotBlank @Size(max = 50) String storeCategoryCode,
            @Schema(description = "The product's display title", example = "Espresso machine, 2-cup")
            @NotBlank @Size(max = 200) String title,
            @Schema(description = "The product's free-form description", nullable = true)
            @Size(max = 2000) String description,
            @Schema(description = "The authoritative unit price in minor units", example = "149900")
            @NotNull @Min(0) Long priceMinor,
            @Schema(description = "ISO-4217 currency code", example = "SAR")
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Schema(description = "Optional reusable goods ProductType ID; omit to preserve the legacy untyped path", nullable = true)
            UUID productTypeId,
            @Schema(description = "Typed PRODUCT-scope values. Each item names an assignmentId and one value field.", nullable = true)
            List<CatalogAttributeValueInput> attributeValues
    ) {
        public RegisterProductRequest {
            attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
        }
        /** Existing clients and direct callers can continue to use pre-C1 constructors. */
        public RegisterProductRequest(String storeCategoryCode, String title, String description,
                                      Long priceMinor, String currency) {
            this(storeCategoryCode, title, description, priceMinor, currency, null, List.of());
        }
        public RegisterProductRequest(String storeCategoryCode, String title, String description,
                                      Long priceMinor, String currency, UUID productTypeId) {
            this(storeCategoryCode, title, description, priceMinor, currency, productTypeId, List.of());
        }
    }

    /** The root's response shape — the same fields the entity declares. */
    public record ProductResponse(
            UUID id,
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID providerId,
            UUID productTypeId,
            StoreProductStatus status
    ) {
        /** Source compatibility for older callers constructing this response shape directly. */
        public ProductResponse(UUID id, String storeCategoryCode, String title, String description,
                               long priceMinor, String currency, UUID providerId) {
            this(id, storeCategoryCode, title, description, priceMinor, currency, providerId, null, StoreProductStatus.ACTIVE);
        }
        public ProductResponse(UUID id, String storeCategoryCode, String title, String description,
                               long priceMinor, String currency, UUID providerId, UUID productTypeId) {
            this(id, storeCategoryCode, title, description, priceMinor, currency, providerId, productTypeId, StoreProductStatus.ACTIVE);
        }

        static ProductResponse of(Product product) {
            return new ProductResponse(product.getId(), product.getStoreCategoryCode(),
                    product.getTitle(), product.getDescription(), product.getPriceMinor(),
                    product.getCurrency(), product.getProviderId(), product.getProductTypeId(), product.getStatus());
        }
    }
}

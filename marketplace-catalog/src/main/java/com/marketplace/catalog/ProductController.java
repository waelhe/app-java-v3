package com.marketplace.catalog;

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
                request.priceMinor(), request.currency()), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductResponse.of(product));
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
            @NotBlank @Size(min = 3, max = 3) String currency
    ) {
    }

    /** The root's response shape — the same fields the entity declares. */
    public record ProductResponse(
            UUID id,
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID providerId
    ) {
        static ProductResponse of(Product product) {
            return new ProductResponse(product.getId(), product.getStoreCategoryCode(),
                    product.getTitle(), product.getDescription(), product.getPriceMinor(),
                    product.getCurrency(), product.getProviderId());
        }
    }
}

package com.marketplace.catalog;

import java.util.UUID;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stage 6 (ADR-0002): the buyer's public product read — the storefront
 * shelf face. ACTIVE products only (the query is the gate): the suspended,
 * archived, soft-deleted product answers the honest 404, and availability
 * rides as the boolean the UX needs (the raw reserved set is the store's
 * own operational record, never a public field).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/storefront", version = "1.0")
public class StorefrontProductController {

    private final StorefrontProductRepository storefrontProductRepository;

    public StorefrontProductController(StorefrontProductRepository storefrontProductRepository) {
        this.storefrontProductRepository = storefrontProductRepository;
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Read one ACTIVE product (public storefront)",
            description = "The buyer's product face: the authoritative price and "
                    + "currency, the shelf availability as a boolean. A suspended, "
                    + "archived or deleted product answers the honest 404.")
    public ResponseEntity<StorefrontProductResponse> product(@PathVariable UUID id) {
        Product product = storefrontProductRepository.findActiveProduct(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
        return ResponseEntity.ok(StorefrontProductResponse.of(product));
    }

    /**
     * The buyer's view — data-minimized by construction: no reserved set,
     * no soft-delete markers, no supplier-internal fields.
     */
    public record StorefrontProductResponse(
            UUID id,
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID sellerId,
            boolean inStock
    ) {
        static StorefrontProductResponse of(Product product) {
            return new StorefrontProductResponse(product.getId(), product.getStoreCategoryCode(),
                    product.getTitle(), product.getDescription(), product.getPriceMinor(),
                    product.getCurrency(), product.getProviderId(),
                    product.getStockQuantity() - product.getReservedQuantity() > 0);
        }
    }
}

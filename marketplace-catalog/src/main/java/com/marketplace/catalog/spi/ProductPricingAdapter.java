package com.marketplace.catalog.spi;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductStatus;
import com.marketplace.catalog.StoreProductStatus;
import com.marketplace.catalog.ProductVariant;
import com.marketplace.catalog.ProductVariantRepository;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 6 (ADR-0002): the catalog module's implementation of the
 * {@link ProductPricingPort} cross-module contract — the authoritative
 * pricing seam the orders cart reads (the {@code ProductLookupAdapter}
 * pattern verbatim; the media seam's twin).
 *
 * <p>{@code readOnly} with {@code REQUIRES_NEW}-safe REQUIRED propagation:
 * the port is called inside the caller's transaction (the cart's add, the
 * placement's freshness check) and joins it — the price read and the line
 * write commit atomically with the placement.
 */
@Component
public class ProductPricingAdapter implements ProductPricingPort {

    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    public ProductPricingAdapter(ProductRepository productRepository,
                                 ProductVariantRepository variantRepository) {
        this.productRepository = productRepository;
        this.variantRepository = variantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPrice priceOf(java.util.UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        return new ProductPrice(product.getId(), product.getProviderId(),
                product.getPriceMinor(), product.getCurrency(), toState(product.getStatus()));
    }

    /**
     * ADR-0012 (the cart-integration slice): the variant's authoritative
     * pricing read — the variant row PLUS its owning product, in the
     * caller's transaction (the cart's add and the placement's freshness
     * check ride the same unit the product read does). The carrier keeps
     * BOTH shelf states separate so the refusing 409 names the shelf that
     * hid the purchase; the currency is the variant's own column (copied
     * from the product at the variant's creation — never client-supplied).
     */
    @Override
    @Transactional(readOnly = true)
    public VariantPrice priceOfVariant(java.util.UUID variantId) {
        ProductVariant variant = variantRepository.findById(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("ProductVariant", variantId));
        Product product = productRepository.findById(variant.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product", variant.getProductId()));
        return new VariantPrice(variant.getId(), product.getId(), product.getProviderId(),
                variant.getPriceMinor(), variant.getCurrency(),
                toState(product.getStatus()), toState(variant.getStatus()));
    }

    private StorefrontState toState(StoreProductStatus status) {
        return switch (status) {
            case DRAFT -> StorefrontState.DRAFT;
            case ACTIVE -> StorefrontState.ACTIVE;
            case SUSPENDED -> StorefrontState.SUSPENDED;
            case ARCHIVED -> StorefrontState.ARCHIVED;
        };
    }

    private StorefrontState toState(ProductStatus status) {
        return switch (status) {
            case ACTIVE -> StorefrontState.ACTIVE;
            case SUSPENDED -> StorefrontState.SUSPENDED;
            case ARCHIVED -> StorefrontState.ARCHIVED;
        };
    }
}

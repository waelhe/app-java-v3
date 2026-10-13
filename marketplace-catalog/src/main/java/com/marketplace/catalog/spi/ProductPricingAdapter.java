package com.marketplace.catalog.spi;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.catalog.ProductStatus;
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

    public ProductPricingAdapter(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public ProductPrice priceOf(java.util.UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        return new ProductPrice(product.getId(), product.getProviderId(),
                product.getPriceMinor(), product.getCurrency(), toState(product.getStatus()));
    }

    private StorefrontState toState(ProductStatus status) {
        return switch (status) {
            case ACTIVE -> StorefrontState.ACTIVE;
            case SUSPENDED -> StorefrontState.SUSPENDED;
            case ARCHIVED -> StorefrontState.ARCHIVED;
        };
    }
}

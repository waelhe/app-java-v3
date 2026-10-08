package com.marketplace.catalog.spi;

import java.util.UUID;

import com.marketplace.catalog.Product;
import com.marketplace.catalog.ProductRepository;
import com.marketplace.shared.api.ProductLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A-17 (C.7): the catalog module's implementation of the media line's
 * product-target seam — the {@code PostLookupAdapter} pattern verbatim
 * (the port in shared/api, the adapter in the owning module's spi
 * package, a read-only transaction, the 404 on absence).
 */
@Component
@Transactional(readOnly = true)
public class ProductLookupAdapter implements ProductLookupPort {

    private final ProductRepository productRepository;

    public ProductLookupAdapter(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public ProductInfo getProductInfo(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        return new ProductInfo(product.getId(), product.getProviderId());
    }
}

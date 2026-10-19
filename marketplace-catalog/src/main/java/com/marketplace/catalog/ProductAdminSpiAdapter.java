package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductAdminSpi;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Implementation of the catalog's administrative projection seam.
 * All commands delegate to the same secured ProductService/ProductTypeService
 * used by REST; this adapter adds no business or authorization rules.
 */
@Component
public class ProductAdminSpiAdapter implements ProductAdminSpi {

    private final ProductService products;
    private final ProductTypeService productTypes;

    public ProductAdminSpiAdapter(ProductService products, ProductTypeService productTypes) {
        this.products = products;
        this.productTypes = productTypes;
    }

    @Override
    public Page<ProductView> listOwnedProducts(Authentication authentication, Pageable pageable) {
        return products.listOwnedProducts(authentication, pageable).map(ProductAdminSpiAdapter::toView);
    }

    @Override
    public ProductView getOwnedProduct(UUID id, Authentication authentication) {
        return toView(products.getOwnedProduct(id, authentication));
    }

    @Override
    public List<ProductTypeView> listProductTypes() {
        return productTypes.listForConfiguration().stream()
                .map(type -> new ProductTypeView(
                        type.getId(), type.getCode(), type.getNameEn(), type.getNameAr(), type.getDescription()))
                .toList();
    }

    @Override
    public ProductView createProduct(CreateProductCommand command, Authentication authentication) {
        Product saved = products.register(new ProductService.RegisterProductCommand(
                command.storeCategoryCode(), command.title(), command.description(), command.priceMinor(),
                command.currency(), command.productTypeId(), List.of()), authentication);
        return toView(saved);
    }

    @Override
    public ProductView updateProduct(UUID id, UpdateProductCommand command, Authentication authentication) {
        return toView(products.updateOwnedProduct(
                id, new ProductService.UpdateProductCommand(command.title(), command.description(), command.priceMinor()),
                authentication));
    }

    @Override
    public ProductView publishProduct(UUID id, Authentication authentication) {
        return toView(products.publishOwnedProduct(id, authentication));
    }

    private static ProductView toView(Product product) {
        return new ProductView(
                product.getId(),
                product.getStoreCategoryCode(),
                product.getTitle(),
                product.getDescription(),
                product.getPriceMinor(),
                product.getCurrency(),
                product.getProviderId(),
                product.getProductTypeId(),
                product.getStatus());
    }
}

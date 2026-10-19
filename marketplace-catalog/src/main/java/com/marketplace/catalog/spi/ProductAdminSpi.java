package com.marketplace.catalog.spi;

import com.marketplace.catalog.StoreProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * Journey-oriented, provider-scoped catalog contract for administrative clients.
 * The interface exposes projection records only; JPA entities and repositories
 * remain inside marketplace-catalog. REST and GraphQL adapters call these same
 * owning use cases and therefore share ownership, validation and publication rules.
 */
@NamedInterface("catalog-spi")
public interface ProductAdminSpi {

    Page<ProductView> listOwnedProducts(Authentication authentication, Pageable pageable);

    ProductView getOwnedProduct(UUID id, Authentication authentication);

    List<ProductTypeView> listProductTypes();

    ProductView createProduct(CreateProductCommand command, Authentication authentication);

    ProductView updateProduct(UUID id, UpdateProductCommand command, Authentication authentication);

    ProductView publishProduct(UUID id, Authentication authentication);

    record CreateProductCommand(
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID productTypeId) { }

    record UpdateProductCommand(String title, String description, long priceMinor) { }

    record ProductView(
            UUID id,
            String storeCategoryCode,
            String title,
            String description,
            long priceMinor,
            String currency,
            UUID providerId,
            UUID productTypeId,
            StoreProductStatus status) { }

    record ProductTypeView(
            UUID id,
            String code,
            String nameEn,
            String nameAr,
            String description) { }
}

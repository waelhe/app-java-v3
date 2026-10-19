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
 * Only projections and command records cross this seam; JPA entities and
 * repositories remain inside marketplace-catalog. REST and GraphQL adapters
 * use the same secured domain services, so ownership and lifecycle invariants
 * have one source of truth.
 */
@NamedInterface("catalog-spi")
public interface ProductAdminSpi {

    Page<ProductView> listOwnedProducts(Authentication authentication, Pageable pageable);
    ProductView getOwnedProduct(UUID id, Authentication authentication);
    List<ProductTypeView> listProductTypes();
    ProductView createProduct(CreateProductCommand command, Authentication authentication);
    ProductView updateProduct(UUID id, UpdateProductCommand command, Authentication authentication);
    ProductView publishProduct(UUID id, Authentication authentication);

    List<AttributeValueView> listProductAttributeValues(UUID productId, Authentication authentication);
    List<AttributeValueView> replaceProductAttributeValues(
            UUID productId, List<AttributeValueInput> values, Authentication authentication);

    List<ProductVariantView> listProductVariants(UUID productId, Authentication authentication);
    List<AttributeValueView> listVariantAttributeValues(UUID variantId, Authentication authentication);
    ProductVariantView createProductVariant(
            UUID productId, CreateProductVariantCommand command, Authentication authentication);

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
            String description,
            List<ProductTypeAttributeView> attributes) {
        public ProductTypeView {
            attributes = attributes == null ? List.of() : List.copyOf(attributes);
        }
    }

    record ProductTypeAttributeView(
            UUID assignmentId,
            UUID attributeDefinitionId,
            String code,
            String nameEn,
            String nameAr,
            String valueType,
            String scope,
            boolean required,
            int position,
            List<ProductAttributeOptionView> options) {
        public ProductTypeAttributeView {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    record ProductAttributeOptionView(
            UUID id, String code, String nameEn, String nameAr, int position) { }

    /** Exact wire values; numberValue remains decimal text until the domain boundary. */
    record AttributeValueInput(
            UUID assignmentId,
            String textValue,
            String numberValue,
            Boolean booleanValue,
            UUID optionId) { }

    record AttributeValueView(
            UUID assignmentId,
            String textValue,
            String numberValue,
            Boolean booleanValue,
            UUID optionId) { }

    record CreateProductVariantCommand(
            String sku,
            String name,
            long priceMinor,
            List<AttributeValueInput> attributeValues) {
        public CreateProductVariantCommand {
            attributeValues = attributeValues == null ? List.of() : List.copyOf(attributeValues);
        }
    }

    record ProductVariantView(
            UUID id,
            UUID productId,
            String sku,
            String name,
            long priceMinor,
            String currency,
            UUID providerId,
            String status,
            int stockQuantity,
            int reservedQuantity) { }
}

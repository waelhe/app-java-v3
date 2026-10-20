package com.marketplace.catalog.spi;

import org.springframework.data.domain.KeysetScrollPosition;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Window;
import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Locale;
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
    Window<ProductView> scrollOwnedProducts(
            Authentication authentication, KeysetScrollPosition position, Limit limit);

    Window<ProductView> scrollOwnedProducts(
            Authentication authentication,
            ProductListFilter filter,
            ProductListSort sort,
            KeysetScrollPosition position,
            Limit limit);
    ProductView getOwnedProduct(UUID id, Authentication authentication);
    List<ProductTypeView> listProductTypes();
    List<StoreCategoryView> listStoreCategories();
    StoreCategoryView getStoreCategory(UUID id);
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

    /** Server-owned, provider-scoped list predicate. Null fields are intentionally omitted. */
    record ProductListFilter(
            String search,
            String storeCategoryCode,
            UUID productTypeId,
            String status,
            String currency,
            Long minPriceMinor,
            Long maxPriceMinor) {
        public ProductListFilter {
            search = search == null || search.isBlank() ? null : search.strip();
            storeCategoryCode = storeCategoryCode == null || storeCategoryCode.isBlank()
                    ? null : storeCategoryCode.strip();
            currency = currency == null || currency.isBlank()
                    ? null : currency.strip().toUpperCase(Locale.ROOT);
            if (search != null && search.length() > 100) {
                throw new IllegalArgumentException("search must be at most 100 characters");
            }
            if (storeCategoryCode != null && storeCategoryCode.length() > 50) {
                throw new IllegalArgumentException("storeCategoryCode must be at most 50 characters");
            }
            if (currency != null && !currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("currency must be a three-letter code");
            }
            if (minPriceMinor != null && minPriceMinor < 0
                    || maxPriceMinor != null && maxPriceMinor < 0) {
                throw new IllegalArgumentException("price bounds must be non-negative minor-unit integers");
            }
            if (minPriceMinor != null && maxPriceMinor != null && minPriceMinor > maxPriceMinor) {
                throw new IllegalArgumentException("minPriceMinor must not exceed maxPriceMinor");
            }
            if ((minPriceMinor != null || maxPriceMinor != null) && currency == null) {
                throw new IllegalArgumentException("currency is required when filtering by price bounds");
            }
            if (status != null && !List.of("DRAFT", "ACTIVE", "SUSPENDED", "ARCHIVED").contains(status)) {
                throw new IllegalArgumentException("status is not a supported product lifecycle value");
            }
        }

        /** Compatibility for callers using the pre-currency filter shape. */
        public ProductListFilter(
                String search, String storeCategoryCode, UUID productTypeId, String status,
                Long minPriceMinor, Long maxPriceMinor) {
            this(search, storeCategoryCode, productTypeId, status, null, minPriceMinor, maxPriceMinor);
        }

        public static ProductListFilter all() {
            return new ProductListFilter(null, null, null, null, null, null, null);
        }
    }

    enum ProductListSortField {
        CREATED_AT("createdAt"),
        TITLE("title"),
        PRICE_MINOR("priceMinor"),
        STATUS("status");

        private final String property;

        ProductListSortField(String property) {
            this.property = property;
        }

        public String property() {
            return property;
        }
    }

    enum ProductListSortDirection {
        ASC,
        DESC
    }

    /** A closed, whitelisted sort vocabulary; arbitrary client property paths are never accepted. */
    record ProductListSort(ProductListSortField field, ProductListSortDirection direction) {
        public ProductListSort {
            field = field == null ? ProductListSortField.CREATED_AT : field;
            direction = direction == null ? ProductListSortDirection.DESC : direction;
        }

        public static ProductListSort defaultSort() {
            return new ProductListSort(ProductListSortField.CREATED_AT, ProductListSortDirection.DESC);
        }
    }

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
            String status) { }

    record StoreCategoryView(UUID id, String code, String nameEn, String nameAr, int position) { }

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

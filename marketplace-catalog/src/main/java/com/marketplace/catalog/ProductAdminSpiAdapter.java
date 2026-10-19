package com.marketplace.catalog;

import com.marketplace.catalog.spi.ProductAdminSpi;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administrative projection seam. It delegates to the same secured
 * ProductService, ProductVariantService, ProductTypeService and attribute
 * services used by REST; this adapter adds no domain or authorization rules.
 */
@Component
public class ProductAdminSpiAdapter implements ProductAdminSpi {

    private final ProductService products;
    private final ProductTypeService productTypes;
    private final ProductTypeAttributeService typeAttributes;
    private final CatalogAttributeService attributes;
    private final ProductVariantService variants;

    public ProductAdminSpiAdapter(ProductService products,
                                  ProductTypeService productTypes,
                                  ProductTypeAttributeService typeAttributes,
                                  CatalogAttributeService attributes,
                                  ProductVariantService variants) {
        this.products = products;
        this.productTypes = productTypes;
        this.typeAttributes = typeAttributes;
        this.attributes = attributes;
        this.variants = variants;
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
        Map<UUID, CatalogAttributeDefinition> definitionsById = attributes.listDefinitions().stream()
                .collect(Collectors.toMap(CatalogAttributeDefinition::getId, Function.identity()));
        return productTypes.listForConfiguration().stream()
                .map(type -> {
                    List<ProductTypeAttributeView> fields = typeAttributes.list(type.getId()).stream()
                            .map(assignment -> {
                                CatalogAttributeDefinition definition = definitionsById.get(
                                        assignment.getAttributeDefinitionId());
                                if (definition == null) {
                                    throw new IllegalStateException(
                                            "ProductType assignment references a missing attribute definition");
                                }
                                List<ProductAttributeOptionView> allowedOptions =
                                        definition.getValueType() == CatalogAttributeType.CHOICE
                                                ? attributes.listOptions(definition.getId()).stream()
                                                .map(option -> new ProductAttributeOptionView(
                                                        option.getId(), option.getCode(), option.getNameEn(),
                                                        option.getNameAr(), option.getPosition()))
                                                .toList()
                                                : List.of();
                                return new ProductTypeAttributeView(
                                        assignment.getId(), definition.getId(), definition.getCode(),
                                        definition.getNameEn(), definition.getNameAr(),
                                        definition.getValueType().name(), assignment.getScope().name(),
                                        assignment.isRequired(), assignment.getPosition(), allowedOptions);
                            })
                            .toList();
                    return new ProductTypeView(
                            type.getId(), type.getCode(), type.getNameEn(), type.getNameAr(),
                            type.getDescription(), fields);
                })
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

    @Override
    public List<AttributeValueView> listProductAttributeValues(UUID productId, Authentication authentication) {
        return products.listOwnedAttributeValues(productId, authentication).stream()
                .map(ProductAdminSpiAdapter::toValueView)
                .toList();
    }

    @Override
    public List<AttributeValueView> replaceProductAttributeValues(
            UUID productId, List<AttributeValueInput> values, Authentication authentication) {
        return products.replaceOwnedAttributeValues(
                        productId, toDomainValues(values), authentication).stream()
                .map(ProductAdminSpiAdapter::toValueView)
                .toList();
    }

    @Override
    public List<ProductVariantView> listProductVariants(UUID productId, Authentication authentication) {
        return variants.listOwnedByProduct(productId, authentication).stream()
                .map(ProductAdminSpiAdapter::toView)
                .toList();
    }

    @Override
    public List<AttributeValueView> listVariantAttributeValues(UUID variantId, Authentication authentication) {
        return variants.listOwnedAttributeValues(variantId, authentication).stream()
                .map(ProductAdminSpiAdapter::toValueView)
                .toList();
    }

    @Override
    public ProductVariantView createProductVariant(
            UUID productId, CreateProductVariantCommand command, Authentication authentication) {
        ProductVariant saved = variants.register(productId,
                new ProductVariantService.RegisterVariantCommand(
                        command.sku(), command.name(), command.priceMinor(), toDomainValues(command.attributeValues())),
                authentication);
        return toView(saved);
    }

    private static List<CatalogAttributeValueInput> toDomainValues(List<AttributeValueInput> values) {
        if (values == null) return List.of();
        return values.stream().map(value -> new CatalogAttributeValueInput(
                value.assignmentId(),
                value.textValue(),
                value.numberValue() == null ? null : new BigDecimal(value.numberValue()),
                value.booleanValue(),
                value.optionId())).toList();
    }

    private static AttributeValueView toValueView(CatalogAttributeValueInput value) {
        BigDecimal number = value.numberValue();
        return new AttributeValueView(value.assignmentId(), value.textValue(),
                number == null ? null : number.stripTrailingZeros().toPlainString(),
                value.booleanValue(), value.optionId());
    }

    private static ProductVariantView toView(ProductVariant variant) {
        return new ProductVariantView(
                variant.getId(), variant.getProductId(), variant.getSku(), variant.getName(),
                variant.getPriceMinor(), variant.getCurrency(), variant.getProviderId(),
                variant.getStatus().name(), variant.getStockQuantity(), variant.getReservedQuantity());
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

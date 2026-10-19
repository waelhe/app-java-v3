package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.StoreProduct;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeOption;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeScope;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeValue;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeValueType;
import com.marketplace.app.graphql.generated.types.StoreProductStatus;
import com.marketplace.app.graphql.generated.types.StoreProductType;
import com.marketplace.app.graphql.generated.types.StoreProductTypeAttribute;
import com.marketplace.app.graphql.generated.types.StoreProductVariant;
import com.marketplace.app.graphql.generated.types.StoreProductVariantStatus;
import com.marketplace.catalog.spi.ProductAdminSpi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.UUID;

/** Maps catalog SPI projections into the DGS CodeGen-owned GraphQL response contract. */
@Mapper(componentModel = "spring")
public interface StoreProductGraphQlMapper {

    @Mapping(target = "id", expression = "java(toTransportId(product.id()))")
    @Mapping(target = "priceMinor", expression = "java(Long.toString(product.priceMinor()))")
    @Mapping(target = "providerId", expression = "java(toTransportId(product.providerId()))")
    @Mapping(target = "productTypeId", expression = "java(toTransportId(product.productTypeId()))")
    @Mapping(target = "status", expression = "java(StoreProductStatus.valueOf(product.status().name()))")
    StoreProduct toResponse(ProductAdminSpi.ProductView product);

    @Mapping(target = "id", expression = "java(toTransportId(productType.id()))")
    StoreProductType toResponse(ProductAdminSpi.ProductTypeView productType);

    @Mapping(target = "assignmentId", expression = "java(toTransportId(attribute.assignmentId()))")
    @Mapping(target = "attributeDefinitionId", expression = "java(toTransportId(attribute.attributeDefinitionId()))")
    @Mapping(target = "valueType", expression = "java(toAttributeValueType(attribute.valueType()))")
    @Mapping(target = "scope", expression = "java(toAttributeScope(attribute.scope()))")
    StoreProductTypeAttribute toResponse(ProductAdminSpi.ProductTypeAttributeView attribute);

    @Mapping(target = "id", expression = "java(toTransportId(option.id()))")
    StoreProductAttributeOption toResponse(ProductAdminSpi.ProductAttributeOptionView option);

    @Mapping(target = "assignmentId", expression = "java(toTransportId(value.assignmentId()))")
    @Mapping(target = "optionId", expression = "java(toTransportId(value.optionId()))")
    StoreProductAttributeValue toResponse(ProductAdminSpi.AttributeValueView value);

    @Mapping(target = "id", expression = "java(toTransportId(variant.id()))")
    @Mapping(target = "productId", expression = "java(toTransportId(variant.productId()))")
    @Mapping(target = "providerId", expression = "java(toTransportId(variant.providerId()))")
    @Mapping(target = "priceMinor", expression = "java(Long.toString(variant.priceMinor()))")
    @Mapping(target = "status", expression = "java(toVariantStatus(variant.status()))")
    StoreProductVariant toResponse(ProductAdminSpi.ProductVariantView variant);

    default String toTransportId(UUID id) {
        return id == null ? null : id.toString();
    }

    default StoreProductAttributeValueType toAttributeValueType(String type) {
        return StoreProductAttributeValueType.valueOf(type);
    }

    default StoreProductAttributeScope toAttributeScope(String scope) {
        return StoreProductAttributeScope.valueOf(scope);
    }

    default StoreProductVariantStatus toVariantStatus(String status) {
        return StoreProductVariantStatus.valueOf(status);
    }
}

package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.StoreProduct;
import com.marketplace.app.graphql.generated.types.StoreCategory;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeOption;
import com.marketplace.app.graphql.generated.types.StoreProductMedia;
import com.marketplace.app.graphql.generated.types.StoreProductMediaStatus;
import com.marketplace.app.graphql.generated.types.StoreProductMediaUpload;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeScope;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeValue;
import com.marketplace.app.graphql.generated.types.StoreProductAttributeValueType;
import com.marketplace.app.graphql.generated.types.StoreProductStatus;
import com.marketplace.app.graphql.generated.types.StoreProductType;
import com.marketplace.app.graphql.generated.types.StoreProductTypeAttribute;
import com.marketplace.app.graphql.generated.types.StoreProductVariant;
import com.marketplace.app.graphql.generated.types.StoreProductVariantStatus;
import com.marketplace.catalog.spi.ProductAdminSpi;
import com.marketplace.shared.api.ProductMediaAdminPort;
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
    @Mapping(target = "status", expression = "java(toProductStatus(product.status()))")
    StoreProduct toResponse(ProductAdminSpi.ProductView product);

    @Mapping(target = "id", expression = "java(toTransportId(category.id()))")
    StoreCategory toResponse(ProductAdminSpi.StoreCategoryView category);

    @Mapping(target = "id", expression = "java(toTransportId(media.id()))")
    @Mapping(target = "productId", expression = "java(toTransportId(media.productId()))")
    @Mapping(target = "sizeBytes", expression = "java(Long.toString(media.sizeBytes()))")
    @Mapping(target = "status", expression = "java(toMediaStatus(media.status()))")
    @Mapping(target = "createdAt", expression = "java(media.createdAt().toString())")
    StoreProductMedia toResponse(ProductMediaAdminPort.MediaAssetView media);

    @Mapping(target = "mediaId", expression = "java(toTransportId(upload.mediaId()))")
    @Mapping(target = "urlLifetimeSeconds", expression = "java(Math.toIntExact(upload.urlLifetime().toSeconds()))")
    StoreProductMediaUpload toResponse(ProductMediaAdminPort.UploadView upload);

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

    default StoreProductMediaStatus toMediaStatus(String status) {
        return StoreProductMediaStatus.valueOf(status);
    }

    default StoreProductStatus toProductStatus(String status) {
        return StoreProductStatus.valueOf(status);
    }

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

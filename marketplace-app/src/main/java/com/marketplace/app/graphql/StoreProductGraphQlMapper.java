package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.StoreProduct;
import com.marketplace.app.graphql.generated.types.StoreProductStatus;
import com.marketplace.app.graphql.generated.types.StoreProductType;
import com.marketplace.catalog.spi.ProductAdminSpi;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** Maps catalog SPI projections into the DGS CodeGen-owned GraphQL response contract. */
@Mapper(componentModel = "spring")
public interface StoreProductGraphQlMapper {

    @Mapping(target = "id", expression = "java(product.id().toString())")
    @Mapping(target = "priceMinor", expression = "java(Long.toString(product.priceMinor()))")
    @Mapping(target = "providerId", expression = "java(product.providerId().toString())")
    @Mapping(target = "productTypeId", expression = "java(product.productTypeId() == null ? null : product.productTypeId().toString())")
    @Mapping(target = "status", expression = "java(StoreProductStatus.valueOf(product.status().name()))")
    StoreProduct toResponse(ProductAdminSpi.ProductView product);

    @Mapping(target = "id", expression = "java(productType.id().toString())")
    StoreProductType toResponse(ProductAdminSpi.ProductTypeView productType);
}

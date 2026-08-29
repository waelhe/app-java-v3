package com.marketplace.app.graphql;

import com.marketplace.shared.api.ProviderListingView;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ServiceMapper {

    @Mapping(target = "name", source = "title")
    @Mapping(target = "price", expression = "java(listing.priceCents() != null ? listing.priceCents().doubleValue() / 100.0 : 0.0)")
    @Mapping(target = "status", expression = "java(\"ACTIVE\".equals(listing.status()) ? \"ACTIVE\" : \"INACTIVE\")")
    ServiceResponse toResponse(ProviderListingView listing);
}
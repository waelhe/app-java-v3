package com.marketplace.app.graphql;

import com.marketplace.catalog.ProviderListing;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ServiceMapper {

    @Mapping(target = "name", source = "title")
    @Mapping(target = "price", expression = "java(listing.getPriceCents() != null ? listing.getPriceCents().doubleValue() / 100.0 : 0.0)")
    @Mapping(target = "status", expression = "java(listing.getStatus() == com.marketplace.catalog.ListingStatus.ACTIVE ? \"ACTIVE\" : \"INACTIVE\")")
    ServiceResponse toResponse(ProviderListing listing);
}

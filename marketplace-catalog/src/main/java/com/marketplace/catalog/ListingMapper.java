package com.marketplace.catalog;

import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ListingMapper {

    @Mapping(target = "price", expression = "java(java.math.BigDecimal.valueOf(listing.getPriceCents(), 2))")
    ListingResponse toResponse(ProviderListing listing);
}

package com.marketplace.catalog;

import com.marketplace.shared.api.ProviderListingView;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ListingMapper {

    @Mapping(target = "price", expression = "java(java.math.BigDecimal.valueOf(listing.getPriceCents(), 2))")
    ListingResponse toResponse(ProviderListing listing);

    @Mapping(target = "price", expression = "java(java.math.BigDecimal.valueOf(listing.priceCents(), 2))")
    ListingResponse toResponse(ProviderListingView listing);
}

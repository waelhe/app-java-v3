package com.marketplace.catalog;

import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.ProviderListingView;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ListingMapper {

    /**
     * L31: the {@code property} block is NOT a mapping product — it is
     * embedded by the controller through {@code PropertyDetailsPort} after
     * the mapping (application association, never JPA). Both targets are
     * ignored so MapStruct never synthesizes a mapping for them.
     *
     * <p>L39: the {@code jsonLd} block follows the same rule — it is
     * composed on the public detail read (the {@code ListingSeoService}
     * leaf), never mapped from the entity.
     */
    @Mapping(target = "price", expression = "java(java.math.BigDecimal.valueOf(listing.getPriceCents(), 2))")
    @Mapping(target = "property", ignore = true)
    @Mapping(target = "withProperty", ignore = true)
    @Mapping(target = "jsonLd", ignore = true)
    @Mapping(target = "withJsonLd", ignore = true)
    ListingResponse toResponse(ProviderListing listing);

    @Mapping(target = "price", expression = "java(java.math.BigDecimal.valueOf(listing.priceCents(), 2))")
    @Mapping(target = "property", ignore = true)
    @Mapping(target = "withProperty", ignore = true)
    @Mapping(target = "jsonLd", ignore = true)
    @Mapping(target = "withJsonLd", ignore = true)
    ListingResponse toResponse(ProviderListingView listing);
}

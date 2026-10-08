package com.marketplace.catalog;

import com.marketplace.shared.api.PropertyDetailsPort;
import com.marketplace.shared.api.ProviderListingView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:35:09+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class ListingMapperImpl implements ListingMapper {

    @Override
    public ListingResponse toResponse(ProviderListing listing) {
        if ( listing == null ) {
            return null;
        }

        UUID id = null;
        String title = null;
        String description = null;
        String category = null;
        String currency = null;
        Integer maxGuests = null;
        Instant createdAt = null;
        Instant updatedAt = null;
        Instant expiresAt = null;
        String pausedReason = null;

        id = listing.getId();
        title = listing.getTitle();
        description = listing.getDescription();
        category = listing.getCategory();
        currency = listing.getCurrency();
        maxGuests = listing.getMaxGuests();
        createdAt = listing.getCreatedAt();
        updatedAt = listing.getUpdatedAt();
        expiresAt = listing.getExpiresAt();
        pausedReason = listing.getPausedReason();

        BigDecimal price = java.math.BigDecimal.valueOf(listing.getPriceCents(), 2);
        PropertyDetailsPort.PropertyView property = null;
        RealEstateListingJsonLd jsonLd = null;

        ListingResponse listingResponse = new ListingResponse( id, title, description, category, price, currency, maxGuests, createdAt, updatedAt, property, expiresAt, pausedReason, jsonLd );

        return listingResponse;
    }

    @Override
    public ListingResponse toResponse(ProviderListingView listing) {
        if ( listing == null ) {
            return null;
        }

        UUID id = null;
        String title = null;
        String description = null;
        String category = null;
        String currency = null;
        Integer maxGuests = null;
        Instant createdAt = null;
        Instant updatedAt = null;
        Instant expiresAt = null;
        String pausedReason = null;

        id = listing.id();
        title = listing.title();
        description = listing.description();
        category = listing.category();
        currency = listing.currency();
        maxGuests = listing.maxGuests();
        createdAt = listing.createdAt();
        updatedAt = listing.updatedAt();
        expiresAt = listing.expiresAt();
        pausedReason = listing.pausedReason();

        BigDecimal price = java.math.BigDecimal.valueOf(listing.priceCents(), 2);
        PropertyDetailsPort.PropertyView property = null;
        RealEstateListingJsonLd jsonLd = null;

        ListingResponse listingResponse = new ListingResponse( id, title, description, category, price, currency, maxGuests, createdAt, updatedAt, property, expiresAt, pausedReason, jsonLd );

        return listingResponse;
    }
}

package com.marketplace.catalog;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ListingResponseTest {

    @Test
    void fromMapsProviderListing() {
        var listing = ProviderListing.create(
                UUID.randomUUID(), "Plumber", "Expert", "services", 5000L);

        var response = ListingResponse.from(listing);

        assertEquals(listing.getId(), response.id());
        assertEquals("Plumber", response.title());
        assertEquals("Expert", response.description());
        assertEquals("services", response.category());
        assertEquals(0, BigDecimal.valueOf(5000, 2).compareTo(response.price()));
        assertEquals("SAR", response.currency());
        assertEquals(listing.getCreatedAt(), response.createdAt());
        assertEquals(listing.getUpdatedAt(), response.updatedAt());
    }
}

package com.marketplace.catalog;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ProviderListingTest {

    @Test
    void createGeneratesId() {
        var providerId = UUID.randomUUID();
        var listing = ProviderListing.create(providerId, "Plumber", "Expert", "services", 5000L);

        assertNotNull(listing.getId());
        assertEquals(providerId, listing.getProviderId());
        assertEquals("Plumber", listing.getTitle());
        assertEquals("Expert", listing.getDescription());
        assertEquals("services", listing.getCategory());
        assertEquals(5000L, listing.getPriceCents());
        assertEquals("SAR", listing.getCurrency());
        assertEquals(ListingStatus.DRAFT, listing.getStatus());
    }

    @Test
    void updateChangesFields() {
        var listing = ProviderListing.create(UUID.randomUUID(), "Old", "Old desc", "old-cat", 1000L);
        listing.update("New", "New desc", "new-cat", 2000L);

        assertEquals("New", listing.getTitle());
        assertEquals("New desc", listing.getDescription());
        assertEquals("new-cat", listing.getCategory());
        assertEquals(2000L, listing.getPriceCents());
    }

    @Test
    void activateChangesStatus() {
        var listing = ProviderListing.create(UUID.randomUUID(), "T", "D", "C", 100L);
        listing.activate();
        assertEquals(ListingStatus.ACTIVE, listing.getStatus());
    }

    @Test
    void pauseChangesStatus() {
        var listing = ProviderListing.create(UUID.randomUUID(), "T", "D", "C", 100L);
        listing.activate();
        listing.pause();
        assertEquals(ListingStatus.PAUSED, listing.getStatus());
    }

    @Test
    void archiveChangesStatus() {
        var listing = ProviderListing.create(UUID.randomUUID(), "T", "D", "C", 100L);
        listing.archive();
        assertEquals(ListingStatus.ARCHIVED, listing.getStatus());
    }

    @Test
    void constructorSetsFields() {
        var id = UUID.randomUUID();
        var providerId = UUID.randomUUID();
        var listing = new ProviderListing(id, providerId, "Title", "Desc", "cat", 3000L);

        assertEquals(id, listing.getId());
        assertEquals(providerId, listing.getProviderId());
        assertEquals("Title", listing.getTitle());
        assertEquals("Desc", listing.getDescription());
        assertEquals("cat", listing.getCategory());
        assertEquals(3000L, listing.getPriceCents());
        assertEquals(ListingStatus.DRAFT, listing.getStatus());
    }
}

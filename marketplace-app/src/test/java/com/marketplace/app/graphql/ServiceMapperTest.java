package com.marketplace.app.graphql;

import com.marketplace.catalog.ProviderListing;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServiceMapperTest {

    private final ServiceMapper mapper = Mappers.getMapper(ServiceMapper.class);

    @Test
    void toResponse_mapsActiveListing() {
        ProviderListing listing = ProviderListing.create(UUID.randomUUID(), "Service Title", "Desc", "cat", 5000L);
        listing.activate();

        ServiceResponse response = mapper.toResponse(listing);

        assertEquals("Service Title", response.name());
        assertEquals("Desc", response.description());
        assertEquals(50.0, response.price());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void toResponse_mapsNonActiveAsInactive() {
        ProviderListing listing = ProviderListing.create(UUID.randomUUID(), "Draft", null, "cat", 3000L);

        ServiceResponse response = mapper.toResponse(listing);

        assertEquals("INACTIVE", response.status());
    }
}

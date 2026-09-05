package com.marketplace.app.graphql;

import com.marketplace.shared.api.ProviderListingView;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServiceMapperTest {

    private final ServiceMapper mapper = Mappers.getMapper(ServiceMapper.class);

    @Test
    void toResponse_mapsActiveListing() {
        ProviderListingView listing = view("Service Title", "Desc", 5000L, "ACTIVE");

        ServiceResponse response = mapper.toResponse(listing);

        assertEquals("Service Title", response.name());
        assertEquals("Desc", response.description());
        assertEquals(50.0, response.price());
        assertEquals("ACTIVE", response.status());
    }

    @Test
    void toResponse_mapsNonActiveAsInactive() {
        ProviderListingView listing = view("Draft", null, 3000L, "DRAFT");

        ServiceResponse response = mapper.toResponse(listing);

        assertEquals("INACTIVE", response.status());
    }

    private static ProviderListingView view(String title, String description, Long priceCents, String status) {
        return new ProviderListingView(UUID.randomUUID(), title, description, "cat", priceCents, "SAR",
                UUID.randomUUID(), status, null, null);
    }
}
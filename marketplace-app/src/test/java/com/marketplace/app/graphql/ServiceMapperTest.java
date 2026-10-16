package com.marketplace.app.graphql;

import com.marketplace.app.graphql.generated.types.Service;
import com.marketplace.app.graphql.generated.types.ServiceStatus;
import com.marketplace.shared.api.ProviderListingView;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The mechanical mapping from the catalog SPI's shared view into the
 * generated transport contract — the Phase 3.3/3.4 increment's
 * equivalence legs at the unit level: same field values as the
 * hand-written record produced, with the {@code ID} rendered to its
 * wire string and the domain status vocabulary typed into the schema's
 * generated enum (the wire-level proof is the integration suite's,
 * which runs unchanged against the generated type).
 */
class ServiceMapperTest {

    private final ServiceMapper mapper = Mappers.getMapper(ServiceMapper.class);

    @Test
    void toResponse_mapsActiveListing() {
        UUID id = UUID.randomUUID();
        ProviderListingView listing = view(id, "Service Title", "Desc", 5000L, "ACTIVE");

        Service response = mapper.toResponse(listing);

        assertEquals(id.toString(), response.getId());
        assertEquals("Service Title", response.getName());
        assertEquals("Desc", response.getDescription());
        assertEquals("cat", response.getCategory());
        assertEquals(50.0, response.getPrice());
        assertEquals("SAR", response.getCurrency());
        assertEquals(ServiceStatus.ACTIVE, response.getStatus());
    }

    @Test
    void toResponse_mapsNonActiveAsInactive() {
        ProviderListingView listing = view(UUID.randomUUID(), "Draft", null, 3000L, "DRAFT");

        Service response = mapper.toResponse(listing);

        assertEquals(ServiceStatus.INACTIVE, response.getStatus());
        assertNull(response.getDescription());
    }

    @Test
    void toResponse_mapsNullIdToNull() {
        ProviderListingView listing = view(null, "No id", null, 1000L, "ACTIVE");

        Service response = mapper.toResponse(listing);

        assertNull(response.getId());
    }

    private static ProviderListingView view(UUID id, String title, String description, Long priceCents, String status) {
        return new ProviderListingView(id, title, description, "cat", priceCents, "SAR",
                UUID.randomUUID(), status, null, null, null);
    }
}

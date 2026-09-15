package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProviderMapperTest {

    private final ProviderMapper mapper = Mappers.getMapper(ProviderMapper.class);

    @Test
    void toResponse_mapsAllFields() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID());

        ProviderResponse response = mapper.toResponse(profile);

        assertEquals(profile.getId(), response.id());
        assertEquals("John", response.displayName());
        assertEquals("Bio", response.bio());
        assertEquals(ProviderStatus.PENDING, response.status());
        // L36: the persona fields ride the plain profile surface too (the
        // public display data of the same profile row).
        assertEquals(ProviderActorType.INDIVIDUAL, response.actorType());
        assertNull(response.agencyName());
        assertNull(response.licenseNumber());
    }

    @Test
    void toResponse_mapsThePersonaFields() {
        ProviderProfile profile = ProviderProfile.create("John", "Bio", UUID.randomUUID(),
                ProviderActorType.AGENCY, "Qudsia Prime", "BR-2026-1149");

        ProviderResponse response = mapper.toResponse(profile);

        assertEquals(ProviderActorType.AGENCY, response.actorType());
        assertEquals("Qudsia Prime", response.agencyName());
        assertEquals("BR-2026-1149", response.licenseNumber());
    }
}

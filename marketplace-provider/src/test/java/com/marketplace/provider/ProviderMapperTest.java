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
    }
}

package com.marketplace.provider;

import java.util.UUID;

public record ProviderResponse(UUID id, UUID userId, String displayName, String bio, String city, String country, String status) {
    public static ProviderResponse from(ProviderProfile profile) {
        return new ProviderResponse(profile.getId(), profile.getUserId(), profile.getDisplayName(), profile.getBio(),
                profile.getCity(), profile.getCountry(), profile.getStatus().name());
    }
}

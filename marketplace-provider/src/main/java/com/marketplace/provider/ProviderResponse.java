package com.marketplace.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderResponse(
        UUID id,
        String displayName,
        String bio,
        ProviderStatus status,
        ProviderActorType actorType,
        String agencyName,
        String licenseNumber,
        Double ratingAverage,
        Instant createdAt,
        Instant updatedAt
) {
}

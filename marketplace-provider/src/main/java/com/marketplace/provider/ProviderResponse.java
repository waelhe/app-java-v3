package com.marketplace.provider;

import java.time.Instant;
import java.util.UUID;

public record ProviderResponse(
        UUID id,
        String displayName,
        String bio,
        ProviderStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}

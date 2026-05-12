package com.marketplace.shared.api;

import java.util.UUID;

public record ProviderSummary(
        UUID id,
        UUID userId,
        String displayName,
        String status,
        String city,
        String country
) {
}

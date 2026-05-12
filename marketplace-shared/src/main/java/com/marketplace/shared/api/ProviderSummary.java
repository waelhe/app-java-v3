package com.marketplace.shared.api;

import java.util.UUID;

public record ProviderSummary(
        UUID id,
        String displayName,
        String status
) {
}

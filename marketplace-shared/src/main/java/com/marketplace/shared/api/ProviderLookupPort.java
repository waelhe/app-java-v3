package com.marketplace.shared.api;

import java.util.UUID;

public interface ProviderLookupPort {
    ProviderSummary getProvider(UUID providerId);

    default void requireActiveProvider(UUID providerId) {
        ProviderSummary provider = getProvider(providerId);
        if (!"ACTIVE".equals(provider.status())) {
            throw new IllegalStateException("Provider is not active: " + providerId);
        }
    }
}

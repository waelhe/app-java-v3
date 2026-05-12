package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for cross-module access to provider identity and lifecycle state.
 */
public interface ProviderLookupPort {

    Optional<ProviderSummary> findById(UUID providerId);
}

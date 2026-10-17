package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/** Cross-module read port for the verified pickup point owned by a provider. */
public interface ProviderFulfillmentPort {
    Optional<ProviderFulfillmentLocation> findVerifiedLocation(UUID providerUserId);
}

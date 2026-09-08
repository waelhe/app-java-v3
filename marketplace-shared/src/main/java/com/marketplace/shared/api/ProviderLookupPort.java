package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for cross-module access to provider identity and lifecycle state.
 */
public interface ProviderLookupPort {

    Optional<ProviderSummary> findById(UUID providerId);

    /**
     * Resolves the provider profile owned by the given user id — the
     * "me" seam of provider-facing endpoints (L20: the ledger reads resolve
     * the calling user's own provider instead of trusting a client-supplied
     * provider id). Empty when the user has no provider profile.
     */
    Optional<ProviderSummary> findByUserId(UUID userId);
}

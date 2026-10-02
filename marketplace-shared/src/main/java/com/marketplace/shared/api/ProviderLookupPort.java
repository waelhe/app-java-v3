package com.marketplace.shared.api;

import java.util.Collection;
import java.util.Map;
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

    /**
     * W4 (yelp-level plan §5 — the reviewer identity &amp; engagement wave,
     * G21): batch resolution for page-sized follow surfaces — the member's
     * "my follows" page resolves its whole page of followed providers in
     * ONE query (the {@code UserLookupPort.findAllByIds} W1 precedent
     * verbatim: "batch resolution via findAllById to avoid N+1 queries").
     * User ids with no provider row are simply absent from the map (the
     * caller's fallback applies — the same contract as the single-id form's
     * empty {@code Optional}).
     */
    Map<UUID, ProviderSummary> findAllByUserIds(Collection<UUID> userIds);
}

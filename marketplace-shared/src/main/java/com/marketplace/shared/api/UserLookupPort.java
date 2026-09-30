package com.marketplace.shared.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface UserLookupPort {

    Optional<UserSummary> findById(UUID userId);

    /**
     * W1 (yelp-level plan §4.4/§4.5): batch resolution for page-sized read
     * surfaces — the reviewer-identity block of a reviews page resolves its
     * whole page's authors in ONE query (the {@code ProviderNameResolver}
     * house rationale: "batch resolution via findAllById to avoid N+1
     * queries"). Ids with no row are simply absent from the map (the
     * caller's fallback applies — the same contract as the single-id form's
     * empty {@code Optional}).
     */
    Map<UUID, UserSummary> findAllByIds(Collection<UUID> userIds);
}


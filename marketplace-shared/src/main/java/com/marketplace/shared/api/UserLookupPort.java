package com.marketplace.shared.api;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's user lookup seam (the SPI in shared-api the
 * cross-module consumers read). The identity module owns the
 * implementation ({@code UserLookupPortImpl} over the users table).
 */
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

    /**
     * R7 (comprehensive-review-ar fix plan §4, Wave 5 — the unified
     * WebSocket identity): resolves a user by the token subject — the
     * SAME resolution seam the REST surface uses
     * ({@code IdentityUserProvider.getCurrentUserId} reads
     * {@code userRepository.findBySubject(jwt.getSubject())} verbatim).
     * The {@code users.subject} column is the unique stable key a
     * minted token's {@code sub} claim carries (an email for the
     * standard flow, the login handle otherwise — the {@code email}
     * column itself is a separate, nullable profile field, never the
     * resolution key). One contract for both surfaces: whatever
     * resolves a REST caller's subject resolves the same WebSocket
     * principal's subject.
     *
     * @param subject the JWT {@code sub} claim value (never the email
     *               column)
     */
    Optional<UserSummary> findBySubject(String subject);
}


package com.marketplace.shared.api;

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

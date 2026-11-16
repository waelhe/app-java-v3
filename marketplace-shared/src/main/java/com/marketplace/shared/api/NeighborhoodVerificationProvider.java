package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Provider boundary reserved for G-N2. Community owns state transitions and must never depend
 * directly on SMS, email, or postal-card implementations.
 */
public interface NeighborhoodVerificationProvider {
    VerificationChallenge begin(UUID membershipId, UUID userId, UUID locationId);

    record VerificationChallenge(String reference) { }
}

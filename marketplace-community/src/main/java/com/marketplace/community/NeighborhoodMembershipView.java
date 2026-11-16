package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The membership read model (L41): the stored facts and nothing else.
 * The geo tree's display names are the public geo surface's concern —
 * the client resolves {@code locationId} there; this view never
 * re-projects another module's state (no invented joins, no stale
 * copies).
 */
public record NeighborhoodMembershipView(
        UUID id,
        UUID userId,
        UUID locationId,
        String verificationState,
        Instant memberSince,
        Instant createdAt,
        Instant updatedAt
) {
    static NeighborhoodMembershipView of(NeighborhoodMembership membership) {
        return new NeighborhoodMembershipView(
                membership.getId(),
                membership.getUserId(),
                membership.getLocationId(),
                membership.getVerificationState().name(),
                membership.getMemberSince(),
                membership.getCreatedAt(),
                membership.getUpdatedAt());
    }
}

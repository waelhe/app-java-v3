package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The membership read model (L51): the stored facts and nothing else —
 * the EventRsvpView projection discipline verbatim (the member stays
 * an opaque UUID; the membership is reached through the group gate, so
 * it never re-projects the group's own state).
 */
public record NeighborhoodGroupMembershipView(
        UUID id,
        UUID groupId,
        UUID memberId,
        Instant createdAt,
        Instant updatedAt
) {
    static NeighborhoodGroupMembershipView of(NeighborhoodGroupMembership membership) {
        return new NeighborhoodGroupMembershipView(
                membership.getId(),
                membership.getGroupId(),
                membership.getMemberId(),
                membership.getCreatedAt(),
                membership.getUpdatedAt());
    }
}

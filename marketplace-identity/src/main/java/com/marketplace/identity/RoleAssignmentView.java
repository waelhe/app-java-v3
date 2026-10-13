package com.marketplace.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10, D-03) — the wire shape of one role
 * assignment (the controllersMustNotDependOnJpaEntities gate: the
 * entity's getters are package-private, this record is the only thing
 * the HTTP boundary speaks — the {@code FollowView} shape verbatim).
 */
public record RoleAssignmentView(
        UUID id,
        UUID userId,
        String role,
        Instant grantedAt,
        UUID grantedBy,
        String source,
        Instant revokedAt
) {

    static RoleAssignmentView of(RoleAssignment assignment) {
        return new RoleAssignmentView(
                assignment.getId(),
                assignment.getUserId(),
                assignment.getRole().name(),
                assignment.getGrantedAt(),
                assignment.getGrantedBy(),
                assignment.getSource(),
                assignment.getRevokedAt());
    }
}

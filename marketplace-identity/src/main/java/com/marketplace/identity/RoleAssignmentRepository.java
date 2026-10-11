package com.marketplace.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10, D-03) — the multi-role assignment's own
 * repository. Every derived query rides Hibernate's {@code @SoftDelete}
 * filter (the {@code FollowRepository} rationale verbatim): the live
* rows are all the reads ever see, and the revoked state rides the
 * {@code revoked_at IS NULL} predicate in the derived method names — the
 * ACTIVE set the V183 effective-authorities view unions is exactly the
 * set these queries answer.
 */
public interface RoleAssignmentRepository extends JpaRepository<RoleAssignment, UUID> {

    /**
     * The ACTIVE assignment of one (user, role) pair — the duplicate
     * grant's gate (the clean 409 before any write) and the revoke's
     * target lookup. The soft-delete filter keeps withdrawn rows out by
     * construction; {@code RevokedAtIsNull} keeps revoked ones out.
     */
    Optional<RoleAssignment> findByUserIdAndRoleAndRevokedAtIsNull(UUID userId, UserRole role);

    /**
     * The account's ACTIVE assignments, newest first (the L32
     * deterministic order — the complete sort key, matching V182's
     * {@code idx_user_role_assignments_user_active}).
     */
    List<RoleAssignment> findByUserIdAndRevokedAtIsNullOrderByGrantedAtDescIdDesc(UUID userId);
}

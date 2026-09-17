package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * The membership anchor's own repository (L41). {@code findByUserId} is
 * the single-active-membership lookup: Hibernate's {@code @SoftDelete}
 * filter hides left memberships from every derived query, so exactly the
 * ACTIVE row is visible — the entity-level twin of V60's partial unique
 * index {@code ON (user_id) WHERE is_deleted = FALSE} (G-N1's default).
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every join, switch and leave is a revision the moderation
 * and export surfaces can read.
 */
public interface NeighborhoodMembershipRepository
        extends JpaRepository<NeighborhoodMembership, UUID>,
        RevisionRepository<NeighborhoodMembership, UUID, Integer> {

    /** The caller's ACTIVE membership, if any (soft-deleted rows filtered). */
    Optional<NeighborhoodMembership> findByUserId(UUID userId);
}

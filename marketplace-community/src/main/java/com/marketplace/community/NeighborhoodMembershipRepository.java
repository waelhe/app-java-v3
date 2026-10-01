package com.marketplace.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    /**
     * The ACTIVE memberships of one neighborhood (L46 — the bridge's own
     * query). Hibernate's {@code @SoftDelete} filter hides left
     * memberships from this derived query exactly as it does from
     * {@link #findByUserId}, so the bridge notifies exactly the members
     * G-N1's active slot admits — a left member is never alerted.
     */
    java.util.List<NeighborhoodMembership> findByLocationId(UUID locationId);

    /**
     * The verification queue's paged read (the lifecycle's own review
     * surface): the ACTIVE memberships of one verification state. The
     * soft-delete filter hides left rows exactly as every derived query
     * does — a LEFT membership holds no reviewable claim (its slot was
     * released; the rejoin's fresh row is the claim that carries state).
     */
    Page<NeighborhoodMembership> findByVerificationState(
            MembershipVerificationState verificationState, Pageable pageable);

    /**
     * The user's LATEST membership verdict INCLUDING soft-deleted rows
     * (#484 review round) — the one read that sees past Hibernate's
     * {@code @SoftDelete} filter, exactly the way the export adapter's
     * native SQL does (the same house channel for soft-deleted facts).
     * The join command reads it before creating a fresh row so the
     * REJECTED verdict follows the USER across a leave/rejoin or a
     * neighborhood switch — without it, a fresh UNVERIFIED row
     * resurrected the write gate the administrator had just closed.
     *
     * @return the stored name of the most recent membership's state
     *         ({@code created_at DESC, id DESC}), or {@code null} for a
     *         user who never held any membership row
     */
    @org.springframework.data.jpa.repository.Query(value =
            "SELECT verification_state FROM neighborhood_memberships "
                    + "WHERE user_id = :userId ORDER BY created_at DESC, id DESC LIMIT 1",
            nativeQuery = true)
    String findLatestVerificationStateIncludingDeleted(
            @org.springframework.data.repository.query.Param("userId") UUID userId);
}

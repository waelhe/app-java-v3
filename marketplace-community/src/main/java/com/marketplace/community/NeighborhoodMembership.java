package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.time.Clock;
import java.util.UUID;

/**
 * One user's membership in exactly one neighborhood (neighborhood
 * community plan §5-L41 — the community layer's anchor entity).
 *
 * <p><b>The domain shape (all plan decisions, all measured):</b>
 * <ul>
 *   <li>{@code userId} and {@code locationId} are plain UUID columns with
 *       NO JPA relation across module boundaries (the V32/V48/V54
 *       discipline) — the user resolves through the identity seams, the
 *       location through {@code GeoLookupPort} (D-N2: the neighborhood IS
 *       a level-3 geo node of the ONE administrative hierarchy; there is
 *       no parallel geography).</li>
 *   <li>{@code verificationState} is the single residency-trust lifecycle;
 *       there is no parallel identity. Provider verification remains behind G-N2.</li>
 *   <li>{@code memberSince} is the domain's own timestamp — the moment
 *       the user (re)joined. It equals the row's creation instant by
 *       construction (a rejoin after leaving is a NEW row, so the
 *       membership history honestly resets) but it is not the audit
 *       column: {@code created_at} is infrastructure bookkeeping,
 *       {@code member_since} is what the product reads.</li>
 *   <li>One ACTIVE membership per user is the G-N1 conservative default,
 *       enforced by V60's partial unique index {@code ON (user_id) WHERE
 *       is_deleted = FALSE} — a left membership releases the slot.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (the V25/V32 lesson);
 * the Envers mirror rides V60 (the V24 convention).
 */
@Entity
@Table(name = "neighborhood_memberships")
@Audited
public class NeighborhoodMembership extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_state", nullable = false, length = 20)
    private MembershipVerificationState verificationState;

    @Column(name = "member_since", nullable = false)
    private Instant memberSince;

    protected NeighborhoodMembership() {
    }

    private NeighborhoodMembership(UUID id, UUID userId, UUID locationId) {
        this.id = id;
        this.userId = userId;
        this.locationId = locationId;
    }

    /**
     * The join factory: a fresh self-declared membership of the given
     * level-3 node. The level/verifiability gates live in the service
     * (before any write); this factory is the honest insert shape.
     *
     * @param clock the injectable system clock — {@code memberSince} is a
     *              schedulable-domain timestamp, so it rides the same
     *              seam every house domain timestamp rides
     */
    public static NeighborhoodMembership join(UUID userId, UUID locationId, Clock clock) {
        NeighborhoodMembership membership =
                new NeighborhoodMembership(UUID.randomUUID(), userId, locationId);
        membership.verificationState = MembershipVerificationState.UNVERIFIED;
        membership.memberSince = clock.instant();
        return membership;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getLocationId() { return locationId; }
    public MembershipVerificationState getVerificationState() { return verificationState; }
    public Instant getMemberSince() { return memberSince; }

    /** UNVERIFIED/PENDING/VERIFIED retain baseline compatibility; rejected claims cannot write. */
    public boolean mayUseCommunityWrites() {
        return verificationState != MembershipVerificationState.REJECTED;
    }

    /**
     * The member-controlled re-application: UNVERIFIED → PENDING only.
     * A REJECTED claim cannot self-reverse (the #484 review round's
     * measured hole: rejection followed by a member-controlled request
     * used to move REJECTED → PENDING, and because the write gate admits
     * every state except REJECTED, the rejected member regained publish/
     * comment/react rights without any administrator ever looking at the
     * claim again). Rejection now stays until an administrator acts —
     * the plan's own "التدفق الأول يدوي إداري فقط" (the review flow is
     * manual-administrative, in BOTH directions); a rejected member's ONE
     * honest recovery path is an administrator's APPROVE (the re-admission
     * lever) — leaving and rejoining carries the verdict forward now, so
     * the fresh row is born REJECTED and never resurrects the write gate.
     */
    public void requestVerification() {
        if (verificationState == MembershipVerificationState.UNVERIFIED) {
            verificationState = MembershipVerificationState.PENDING;
        }
    }

    public void approveVerification() {
        if (verificationState != MembershipVerificationState.PENDING
                && verificationState != MembershipVerificationState.REJECTED) {
            throw new IllegalStateException("Only PENDING or REJECTED memberships can be approved");
        }
        verificationState = MembershipVerificationState.VERIFIED;
    }

    public void rejectVerification() {
        if (verificationState != MembershipVerificationState.PENDING) {
            throw new IllegalStateException("Only PENDING memberships can be rejected");
        }
        verificationState = MembershipVerificationState.REJECTED;
    }

    /**
     * The verdict-carrying birth (#484 review round, second path): a
     * rejoin or switch by a user whose LATEST membership row —
     * INCLUDING the soft-deleted — carried the REJECTED verdict is born
     * REJECTED, never UNVERIFIED. Without this, leaving and rejoining
     * (or switching neighborhoods) minted a fresh UNVERIFIED row and
     * resurrected the write gate the administrator had just closed —
     * the rejection followed the row, not the user, so it was
     * unenforceable against a determined member. The refusal verdict is
     * the one admin decision that follows the USER (every other state
     * births UNVERIFIED — the status quo: the fresh claim carries its
     * own state); {@code memberSince} still restarts honestly — the
     * clock is a time fact, the verdict is a trust fact.
     *
     * <p>Package-private by design: only the service's join command
     * performs this birth, after reading the user's latest verdict
     * through the repository's including-deleted native read.
     */
    void inheritRejectedVerdict() {
        this.verificationState = MembershipVerificationState.REJECTED;
    }
}

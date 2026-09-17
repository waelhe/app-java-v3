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
 *   <li>{@code verificationState} carries {@code SELF_DECLARED} only
 *       (D-N3 — {@link MembershipVerificationState} documents the G-N2
 *       reservation); the V60 CHECK backs the floor at the database.</li>
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
        membership.verificationState = MembershipVerificationState.SELF_DECLARED;
        membership.memberSince = clock.instant();
        return membership;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getLocationId() { return locationId; }
    public MembershipVerificationState getVerificationState() { return verificationState; }
    public Instant getMemberSince() { return memberSince; }
}

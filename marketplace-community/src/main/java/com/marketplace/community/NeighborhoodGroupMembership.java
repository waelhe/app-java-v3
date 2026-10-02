package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L51 (the Nextdoor-2026 completeness wave — gap #6, the neighbors
 * groups): one member's single live membership in one live group —
 * the groups board's join write.
 *
 * <p><b>The domain shape (the V73 PostReaction / V83 EventRsvp
 * discipline verbatim):</b>
 * <ul>
 *   <li>{@code groupId} is the plan's sanctioned INTERNAL reference (the
 *       V7 {@code messages.conversation_id} / V61 {@code post_comments} /
 *       V73 {@code post_reactions} / V83 {@code event_rsvps} precedent):
 *       a plain UUID column in Java — no JPA relation to traverse — with
 *       a real FK inside the module's own boundary in V95. The
 *       memberships follow their group in the reads (an unknown or
 *       soft-deleted group's memberships are absent exactly as the
 *       group itself is — the same is_deleted semantics the seats
 *       ride).</li>
 *   <li>{@code memberId} is a plain UUID column with NO JPA relation
 *       across module boundaries (the V32/V48/V60 discipline) — it lives
 *       in the users.id space and arrives through the identity seams.</li>
 *   <li>One live membership per member per group is the product's own
 *       «عضوية واحدة لكل جار»: the service's explicit 409 comes first,
 *       the V95 partial unique index is the backstop (the V64/V73/V83
 *       precedent verbatim), and a leave frees the seat for a fresh
 *       join.</li>
 *   <li>No enumerated column rides here — the membership IS the fact,
 *       one shape, no vocabulary (the reaction's and the seat's own
 *       reasoning: belonging is binary in the product's contract).</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * Hibernate's {@code @SoftDelete} makes the leave a soft delete (the
 * row stays — b-5's retention, the Envers trail keeps every membership
 * taken and left — and the reads stop returning it). The b-2 export
 * seam reads the member's membership rows (identifiers-and-timestamps,
 * the seat export's own class); there is no authored text to purge
 * (the b-3 seam's ids-only reasoning verbatim).
 */
@Entity
@Table(name = "neighborhood_group_memberships")
@Audited
public class NeighborhoodGroupMembership extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "group_id", nullable = false)
    private UUID groupId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    protected NeighborhoodGroupMembership() {
    }

    private NeighborhoodGroupMembership(UUID id, UUID groupId, UUID memberId) {
        this.id = id;
        this.groupId = groupId;
        this.memberId = memberId;
    }

    /**
     * The join factory: a fresh live membership in a live group by a
     * member of the group's own neighborhood. All gates (the group's
     * liveness, the active membership in its location, the
     * one-membership check) live in the service — before any write;
     * this factory is the honest insert shape.
     */
    public static NeighborhoodGroupMembership join(UUID groupId, UUID memberId) {
        return new NeighborhoodGroupMembership(UUID.randomUUID(), groupId, memberId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getGroupId() { return groupId; }
    public UUID getMemberId() { return memberId; }
}

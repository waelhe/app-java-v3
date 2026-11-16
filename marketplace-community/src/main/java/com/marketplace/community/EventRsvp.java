package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * one member's single live seat on one live event — the board's
 * attendance write, the RSVP.
 *
 * <p><b>The domain shape (the V73 PostReaction discipline verbatim):</b>
 * <ul>
 *   <li>{@code eventId} is the plan's sanctioned INTERNAL reference (the
 *       V7 {@code messages.conversation_id} / V61 {@code post_comments} /
 *       V73 {@code post_reactions} precedent): a plain UUID column in
 *       Java — no JPA relation to traverse — with a real FK inside the
 *       module's own boundary in V83. The RSVPs follow their event in
 *       the reads (an unknown or soft-deleted event's seats are absent
 *       exactly as the event itself is — the same is_deleted semantics
 *       the comments and reactions ride).</li>
 *   <li>{@code memberId} is a plain UUID column with NO JPA relation
 *       across module boundaries (the V32/V48/V60 discipline) — it lives
 *       in the users.id space and arrives through the identity seams.</li>
 *   <li>One live seat per member per event is the product's own «مقعد
 *       واحد لكل عضو»: the service's explicit 409 comes first, the V83
 *       partial unique index is the backstop (the V64/V73 precedent
 *       verbatim), and an un-RSVP frees the seat for a fresh one.</li>
 *   <li>No enumerated column rides here — the RSVP IS the fact, one
 *       shape, no vocabulary (the reaction's own reasoning: attendance
 *       is binary in the product's contract; «تطوّع» as a distinct seat
 *       kind is a product decision that would arrive as a widening
 *       migration, never silently).</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * Hibernate's {@code @SoftDelete} makes the un-RSVP a soft delete (the
 * row stays — b-5's retention, the Envers trail keeps every seat taken
 * and freed — and the reads stop returning it). The purge seam empties
 * the member's RSVP rows when the account closes (b-3).
 */
@Entity
@Table(name = "event_rsvps")
@Audited
public class EventRsvp extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    protected EventRsvp() {
    }

    private EventRsvp(UUID id, UUID eventId, UUID memberId) {
        this.id = id;
        this.eventId = eventId;
        this.memberId = memberId;
    }

    /**
     * The RSVP factory: a fresh live seat on a live event by a member
     * of the event's own neighborhood. All gates (the event's liveness,
     * the active membership in its location, the one-seat check, and
     * the capacity count) live in the service — before any write; this
     * factory is the honest insert shape.
     */
    public static EventRsvp rsvp(UUID eventId, UUID memberId) {
        return new EventRsvp(UUID.randomUUID(), eventId, memberId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getMemberId() { return memberId; }
}

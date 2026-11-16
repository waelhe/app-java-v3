package com.marketplace.community;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

/**
 * L52 (the Nextdoor-2026 completeness wave — gap #7, the polls): one
 * member's single live vote on one poll — the registered contract's
 * own real write ({@code POST /polls/{id}/vote}: «الكتابة الحقيقية =
 * صوت واحد لكل عضو», docs/product-charter.md §7/7-4).
 *
 * <p><b>The domain shape (all registered-contract decisions, all
 * measured):</b>
 * <ul>
 *   <li>{@code pollId} and {@code optionId} are the sanctioned
 *       INTERNAL references (the V7/V61/V73/V83/V91 precedent
 *       verbatim): plain UUID columns in Java, real FKs in SQL — the
 *       vote lives inside the poll's own aggregate boundary.</li>
 *   <li>{@code memberId} is a plain UUID column with NO relation
 *       across module boundaries (the media/notifications discipline —
 *       the users table lives in the identity module's space).</li>
 *   <li>The ONE integrity rule the whole wave owns: ONE live vote per
 *       member per poll — the V64/V73/V83/V91 partial-unique
 *       discipline verbatim (the service's explicit 409 comes first,
 *       the constraint is the backstop, and a withdrawn — soft-deleted
 *       — vote frees the member to vote again: the seat-frees-itself
 *       shape every id-pair toggle since the reactions has ridden).</li>
 * </ul>
 *
 * <p>The vote row carries no authored text at all — it is the
 * lightest id-pair write the platform owns (the postReact/eventRsvp/
 * groupMembership model verbatim), and the rate budget follows that
 * class (the 30/min light-write budget, not the authored-record one).
 * Every BaseEntity column present from day one (V25/V32 lesson); the
 * Envers mirror rides V100 (the V24 convention).
 */
@Entity
@Table(name = "neighborhood_poll_votes")
@Audited
public class NeighborhoodPollVote extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(name = "option_id", nullable = false)
    private UUID optionId;

    @Column(name = "member_id", nullable = false)
    private UUID memberId;

    protected NeighborhoodPollVote() {
    }

    private NeighborhoodPollVote(UUID id, UUID pollId, UUID optionId, UUID memberId) {
        this.id = id;
        this.pollId = pollId;
        this.optionId = optionId;
        this.memberId = memberId;
    }

    /**
     * The voting factory: one member's one live vote for one option of
     * one poll — the entity's own honest insert shape. The service's
     * gate order (the poll's honest 404, the option's own 404/400, the
     * writable membership 403, the one-vote 409) lands BEFORE this
     * insert; the V100 partial unique index is the backstop.
     */
    static NeighborhoodPollVote cast(UUID pollId, UUID optionId, UUID memberId) {
        return new NeighborhoodPollVote(UUID.randomUUID(), pollId, optionId, memberId);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPollId() { return pollId; }
    public UUID getOptionId() { return optionId; }
    public UUID getMemberId() { return memberId; }
}

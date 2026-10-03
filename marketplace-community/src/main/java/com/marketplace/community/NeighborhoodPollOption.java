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
 * poll's own answer choice — the option row the design's card renders
 * as one percentage bar (the registered contract's
 * question/options/votes shape: every option carries a LIVE vote
 * count, earned by real vote rows).
 *
 * <p><b>The domain shape (all registered-contract decisions, all
 * measured):</b>
 * <ul>
 *   <li>{@code pollId} is the sanctioned INTERNAL reference (the V7
 *       messages.conversation_id / V61 post_comments.post_id / V73
 *       post_reactions.post_id / V83 event_rsvps.event_id / V91
 *       memberships.group_id precedent verbatim): a plain UUID column
 *       in Java, a real FK in SQL — the poll and its options are one
 *       aggregate inside the module's own boundary. There is NO JPA
 *       relation mapped (the house discipline — the id travels, the
 *       boundary never crosses).</li>
 *   <li>{@code label} is bounded at 200 characters — the house
 *       one-line display bound (the posts'/events'/market items'/
 *       groups' own shape; the design's own option rows are one-line
 *       display strings: «الفجر — ٥:٣٠ إلى ٨:٠٠»).</li>
 *   <li>{@code position} is the option's OWN display ordinal (0-based,
 *       the author's submission order) — the card renders the options
 *       in the author's own order, and the board read sorts on the
 *       pair (position, id) so two options authored in the same
 *       position can never shake the display order (D-N5's complete
 *       key discipline at the option level). The V100 CHECK keeps the
 *       floor honest (non-negative).</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * the Envers mirror rides V100 (the V24 convention). An option-edit
 * write (relabeling, reordering, adding a sixth option after
 * publishing) is a PRODUCT decision with no registered surface yet —
 * the creation write authors the full 2–5 option set as one unit, and
 * nothing edits it afterward (the design's own card has no edit
 * control — the honest measured shape).
 */
@Entity
@Table(name = "neighborhood_poll_options")
@Audited
public class NeighborhoodPollOption extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "poll_id", nullable = false)
    private UUID pollId;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "position", nullable = false)
    private int position;

    protected NeighborhoodPollOption() {
    }

    private NeighborhoodPollOption(UUID id, UUID pollId, String label, int position) {
        this.id = id;
        this.pollId = pollId;
        this.label = label;
        this.position = position;
    }

    /**
     * The authoring factory: one answer choice of one poll, at its own
     * submission ordinal — the entity's own honest insert shape. The
     * creation write is the shape's ONLY caller (the poll and its full
     * option set are one authored unit); the option-editing write,
     * when the product decides it, rides this same shape.
     */
    static NeighborhoodPollOption optionOf(UUID pollId, String label, int position) {
        return new NeighborhoodPollOption(UUID.randomUUID(), pollId, label, position);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getPollId() { return pollId; }
    public String getLabel() { return label; }
    public int getPosition() { return position; }
}

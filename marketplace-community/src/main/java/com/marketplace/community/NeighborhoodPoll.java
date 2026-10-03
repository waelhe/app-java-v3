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
 * neighborhood's live question «استطلاع رأي» — the featured zone's
 * interactive poll unit (the gap analysis's own registered contract,
 * docs/nextdoor-gap-analysis.md §3: {@code NeighborhoodPoll} +
 * {@code POST /polls/{id}/vote}; the frontend's registered shape
 * docs/product-charter.md §7/7-4 — question/options/votes, the real
 * write = one vote per member).
 *
 * <p><b>The domain shape (all registered-contract decisions, all
 * measured):</b>
 * <ul>
 *   <li>{@code locationId} is a plain UUID column with NO JPA relation
 *       across module boundaries (the V32/V48/V60/V61/V83/V90/V91
 *       discipline) — it lives in the geo_locations.id space and is
 *       level-checked at authoring time through {@code GeoLookupPort}
 *       (the create write carries the L41 publish gate verbatim — the
 *       events'/market items' own shape; the vote gate reads the
 *       stored fact instead of re-resolving it, the L51 join's own
 *       reasoning).</li>
 *   <li>{@code question} is bounded at 200 characters — the house
 *       {@code provider_listings.title} limit (V2's own documented
 *       bound, the posts'/events'/market items'/groups' own shape). The
 *       registered contract carries ONE question; the card renders it
 *       as the poll's whole display title.</li>
 *   <li>{@code authorLabel} is bounded at 200 characters — the design's
 *       own author display (a committee/role label, NOT a person:
 *       «لجنة تطوير الحي» — the S8 display dataset's measured shape).
 *       The creation write carries the label the author publishes under
 *       — the registered contract's own field, never a derived person
 *       identity (the pseudonymization discipline's honest mirror).</li>
 *   <li>NO enumerated column rides here — and that is the measured
 *       shape (the V91 reasoning verbatim): the registered contract
 *       carries question/options/author with no category vocabulary,
 *       so the V100 migration pins no vocabulary CHECK. The ONE
 *       integrity rule the domain owns — one live vote per member per
 *       poll — lives on the vote entity's partial unique index, not
 *       here.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * the Envers mirror rides V100 (the V24 convention). The poll's
 * options are {@link NeighborhoodPollOption} rows (one aggregate inside
 * the module's own boundary — the option's {@code pollId} is the
 * sanctioned INTERNAL reference) and the votes are
 * {@link NeighborhoodPollVote} rows. A poll-close write (freezing the
 * question or the options) is a PRODUCT decision that has no
 * registered surface yet — never a silent one.
 */
@Entity
@Table(name = "neighborhood_polls")
@Audited
public class NeighborhoodPoll extends BaseEntity {

    @Id
    private UUID id;

    /** The geo tree node — level 3 (neighborhood) only, the L41 authoring gate's own target. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "question", nullable = false, length = 200)
    private String question;

    /** The committee/role label the poll publishes under — a display string, never a person identity. */
    @Column(name = "author_label", nullable = false, length = 200)
    private String authorLabel;

    protected NeighborhoodPoll() {
    }

    private NeighborhoodPoll(UUID id, UUID locationId, String question, String authorLabel) {
        this.id = id;
        this.locationId = locationId;
        this.question = question;
        this.authorLabel = authorLabel;
    }

    /**
     * The authoring factory: a fresh poll of exactly one neighborhood,
     * ONE question, published under one committee/role label — the
     * entity's own honest insert shape. The options ride the same
     * creation transaction (the service's own aggregate write — the
     * poll and its options are one authored unit, exactly as the
     * registered contract reads: a poll without its 2–5 options is
     * never a valid intermediate state on the wire). No clock
     * parameter: {@code createdAt} is the auditing listener's own
     * stamp (BaseEntity's @CreatedDate) — the board's newest-first
     * order key.
     */
    static NeighborhoodPoll authored(UUID locationId, String question, String authorLabel) {
        return new NeighborhoodPoll(UUID.randomUUID(), locationId, question, authorLabel);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getLocationId() { return locationId; }
    public String getQuestion() { return question; }
    public String getAuthorLabel() { return authorLabel; }
}

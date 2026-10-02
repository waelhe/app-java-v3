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
 * groups): one neighborhood's standing specialist club — the groups
 * board's unit (the plan's own §7 gate G-N6, opened by the owner's
 * standing Nextdoor-2026 directive).
 *
 * <p><b>The domain shape (all gap-analysis decisions, all measured):</b>
 * <ul>
 *   <li>{@code locationId} is a plain UUID column with NO JPA relation
 *       across module boundaries (the V32/V48/V60/V61/V83/V90
 *       discipline) — it lives in the geo_locations.id space and is
 *       level-checked at authoring time (every seeded group points at
 *       the geo seed's closed level-3 skeleton; the geo seed IS the
 *       authoring gate this wave — there is no group-creation write
 *       yet, a documented product decision inside the opened G-N6
 *       window).</li>
 *   <li>{@code name} is bounded at 200 characters — the house
 *       {@code provider_listings.title} limit (V2's own documented
 *       bound, the posts'/events'/market items' own shape). The
 *       registered contract (§7.7/6) carries name/description/members
 *       — the club's display name.</li>
 *   <li>{@code description} is TEXT — the one-line description the
 *       design's own rows carry («تجمّع يومي 5:30 فجراً», «نقاش
 *       الباصات والأنشطة» — the display meta's own second half,
 *       measured from the owner's design dataset verbatim).</li>
 *   <li>NO enumerated column rides here — the registered contract has
 *       no category, and the V91 migration accordingly pins no CHECK
 *       (the V44 locking shape exists to guard vocabularies; a CHECK
 *       without a vocabulary is decoration, and decoration is debt).
 *       The display icon/tone derivations stay display-side (the
 *       frontend's own seam, the market's category-icon discipline's
 *       honest mirror for a vocabulary-free entity).</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * the Envers mirror rides V91 (the V24 convention). A group-creation
 * write (and with it an author column and the L41 publish gate) is
 * the opened window's next PRODUCT decision — never a silent one.
 */
@Entity
@Table(name = "neighborhood_groups")
@Audited
public class NeighborhoodGroup extends BaseEntity {

    @Id
    private UUID id;

    /** The geo tree node — level 3 (neighborhood) only, the seed's own authoring gate. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    protected NeighborhoodGroup() {
    }

    private NeighborhoodGroup(UUID id, UUID locationId, String name, String description) {
        this.id = id;
        this.locationId = locationId;
        this.name = name;
        this.description = description;
    }

    /**
     * The founding factory: a fresh club of exactly one neighborhood —
     * the entity's own honest insert shape. THIS wave the clubs are
     * authored by the seed (raw SQL against the geo seed's closed
     * level-3 skeleton — there is no founding WRITE yet, a documented
     * product decision inside the opened G-N6 window), so the shape's
     * direct callers are the module's own tests; the founding write,
     * when the product decides it, rides this same shape through the
     * L41 publish gate. No clock parameter: {@code createdAt} is the
     * auditing listener's own stamp (BaseEntity's @CreatedDate) — the
     * board's historical order key.
     */
    static NeighborhoodGroup founded(UUID locationId, String name, String description) {
        return new NeighborhoodGroup(UUID.randomUUID(), locationId, name, description);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getLocationId() { return locationId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}

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
import java.util.UUID;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * one member's organized gathering in exactly one neighborhood — the
 * events board's unit (the plan's own §7 gate «الأحداث المهيكلة —
 * طلب منتج فعلي» opened by the owner's Nextdoor-2026 directive).
 *
 * <p><b>The domain shape (all gap-analysis decisions, all measured):</b>
 * <ul>
 *   <li>{@code authorId} and {@code locationId} are plain UUID columns
 *       with NO JPA relation across module boundaries (the
 *       V32/V48/V60/V61 discipline) — the author resolves through the
 *       identity seams, the location through {@code GeoLookupPort}
 *       (D-N2: level-3 of the ONE administrative hierarchy,
 *       level-checked at publish through the same L41 gate).</li>
 *   <li>{@code category} is the board's one filter axis; the V83 CHECK
 *       pins the SQL membership guard (D-N7). The vocabulary is the
 *       product's own five chips ({@link EventCategory}).</li>
 *   <li>{@code title} is bounded at 200 characters — the house
 *       {@code provider_listings.title} limit (V2's own documented
 *       bound); {@code description} is unbounded TEXT, the community
 *       domain's authored text — the post body's own shape.</li>
 *   <li>{@code startsAt} is strictly in the future at creation (the
 *       service's 400 — the board is forward-looking by construction);
 *       {@code endsAt} is absent or strictly after {@code startsAt}
 *       (the V83 time-order CHECK).</li>
 *   <li>{@code locationLabel} and {@code organizerLabel} are the
 *       product's own display labels — the in-neighborhood meeting
 *       spot's name and the organizing body's name as the organizer
 *       wrote them (the design's form fields, bounded like titles).</li>
 *   <li>{@code registration} + {@code capacity} are ONE rule
 *       ({@link EventRegistration}'s javadoc): OPEN carries no
 *       capacity, the two seated states carry a strictly positive
 *       one — the V83 CHECK is the backstop.</li>
 *   <li>{@code featured} is a read-side flag the create contract does
 *       NOT accept (the owner's own form has no nomination field): the
 *       column rides from day one so the read contract is complete
 *       (the V25/V32 lesson) — surfacing a curation write (an owner
 *       surface or an admin panel) is a documented product decision,
 *       never a silent one. The board's «مبادرة الأسبوع» takes the
 *       first featured row and renders nothing when none is.</li>
 * </ul>
 *
 * <p>Every BaseEntity column present from day one (V25/V32 lesson);
 * the Envers mirror rides V83 (the V24 convention). The author's own
 * delete is the house soft delete — the row stays (b-5's retention),
 * the reads stop returning it, and the RSVPs follow in the read path
 * (the aggregate's own is_deleted semantics — a deleted event's seats
 * are absent exactly as the event itself is). The purge seam (b-3)
 * empties the authored text when the account closes.
 */
@Entity
@Table(name = "neighborhood_events")
@Audited
public class NeighborhoodEvent extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** The geo tree node — level 3 (neighborhood) only, gated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20)
    private EventCategory category;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "location_label", nullable = false, length = 200)
    private String locationLabel;

    @Column(name = "organizer_label", nullable = false, length = 200)
    private String organizerLabel;

    @Column(name = "capacity")
    private Integer capacity;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration", nullable = false, length = 30)
    private EventRegistration registration;

    @Column(name = "featured", nullable = false)
    private boolean featured;

    protected NeighborhoodEvent() {
    }

    private NeighborhoodEvent(UUID id, UUID authorId, UUID locationId) {
        this.id = id;
        this.authorId = authorId;
        this.locationId = locationId;
    }

    /**
     * The organize factory: a fresh live event. The membership, level,
     * time and registration/capacity gates live in the service — before
     * any write; this factory is the honest insert shape. No clock
     * parameter: {@code createdAt} is the auditing listener's own stamp
     * (BaseEntity's @CreatedDate), and the future-time gate reads the
     * service's injectable clock — the domain rows carry no
     * hand-stamped timestamps of their own.
     */
    public static NeighborhoodEvent event(UUID authorId, UUID locationId,
                                          EventCategory category, String title, String description,
                                          Instant startsAt, Instant endsAt,
                                          String locationLabel, String organizerLabel,
                                          Integer capacity, EventRegistration registration) {
        NeighborhoodEvent event = new NeighborhoodEvent(UUID.randomUUID(), authorId, locationId);
        event.category = category;
        event.title = title;
        event.description = description;
        event.startsAt = startsAt;
        event.endsAt = endsAt;
        event.locationLabel = locationLabel;
        event.organizerLabel = organizerLabel;
        event.capacity = capacity;
        event.registration = registration;
        event.featured = false;
        return event;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getAuthorId() { return authorId; }
    public UUID getLocationId() { return locationId; }
    public EventCategory getCategory() { return category; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getLocationLabel() { return locationLabel; }
    public String getOrganizerLabel() { return organizerLabel; }
    public Integer getCapacity() { return capacity; }
    public EventRegistration getRegistration() { return registration; }
    public boolean isFeatured() { return featured; }
}

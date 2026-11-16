package com.marketplace.catalog;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.envers.Audited;

import java.time.LocalDate;
import java.util.UUID;

/**
 * L40 (realestate systems plan §5 — view analytics): one listing's view
 * count for one UTC day — the plan's aggregate table ("جدول تجميعي
 * listing_views_daily (listing_id, view_date, count)"; the count column is
 * named {@code view_count} because a column literally named {@code count}
 * collides with the SQL aggregate in every query that reads the table).
 *
 * <p><b>How a row changes — the L21 stored-aggregate pattern, not a native
 * upsert:</b> the plan's wording says "زيادة ذرّية بسيطة (INSERT ON
 * CONFLICT DO UPDATE)", but the same plan binds D-R8: the aggregate is
 * audited "تجميعي يُدقَّق كالكيانات". Those two are in tension — Envers
 * records entity operations only; a native upsert bypasses the listeners
 * and leaves the {@code _aud} mirror a dead empty shell. The increment
 * therefore rides the pessimistic-locked read-modify-write the house
 * already uses for stored aggregates (L21 {@code refreshRatingAverage}
 * → {@code findByIdForUpdate}); the atomicity the upsert existed for is
 * preserved by the UNIQUE (listing_id, view_date) constraint (the insert
 * race surfaces as a constraint violation retried in a NEW transaction —
 * PostgreSQL aborts the transaction after a violation, the retry cannot
 * share it) plus the row lock serializing concurrent {@code +1}s.
 *
 * <p><b>Day semantics:</b> {@code viewDate} is the UTC day of the view
 * (the house timestamps are UTC instants; the container clock is UTC).
 * The Redis dedup marker is TTL-based (24h from the visitor's FIRST
 * view), so a bucket boundary and a dedup boundary can disagree by hours
 * — the plan's own documented trade ("Redis TTL يوم").
 */
@Entity
@Table(name = "listing_views_daily",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_listing_views_daily_listing_date",
                columnNames = {"listing_id", "view_date"}))
@Audited
public class ListingViewsDaily extends BaseEntity {

    /** A row exists only because at least one deduplicated view happened (CHECK view_count >= 1). */
    static final long FIRST_VIEW_COUNT = 1L;

    @Id
    private UUID id;

    /** The viewed listing — plain UUID, no FK (the V20/V32/V48/V52 discipline). */
    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    /** The UTC day of the counted views. */
    @Column(name = "view_date", nullable = false)
    private LocalDate viewDate;

    /** That day's deduplicated view count — monotonic, >= 1 by the CHECK. */
    @Column(name = "view_count", nullable = false)
    private long viewCount;

    protected ListingViewsDaily() {
        // JPA
    }

    private ListingViewsDaily(UUID id, UUID listingId, LocalDate viewDate, long viewCount) {
        this.id = id;
        this.listingId = listingId;
        this.viewDate = viewDate;
        this.viewCount = viewCount;
    }

    /**
     * The first deduplicated view of one listing-day — the row the unique
     * constraint guards. Ids are application-assigned UUIDs (the D-I7
     * lesson: no sequences, so no missing-sequence privilege can break the
     * surface silently).
     */
    static ListingViewsDaily firstView(UUID listingId, LocalDate viewDate) {
        return new ListingViewsDaily(UUID.randomUUID(), listingId, viewDate, FIRST_VIEW_COUNT);
    }

    /** The atomic +1 — called with the row locked (see the class javadoc). */
    void addView() {
        this.viewCount++;
    }

    UUID getListingId() {
        return listingId;
    }

    LocalDate getViewDate() {
        return viewDate;
    }

    long getViewCount() {
        return viewCount;
    }

    @Override
    public UUID getId() {
        return id;
    }
}

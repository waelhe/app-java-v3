package com.marketplace.provider;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): one declared working-hours
 * window — the {@code business_hours} row (V96). The Yelp business page's
 * most basic block: «ساعات عمل (business_hours بمفتاح فريد مزود×يوم...)».
 *
 * <p><b>Declared schedule, never a booking surface (G11's own evidence):</b>
 * the availability module's {@code availability_slots} are bookable
 * individual appointments; this row is the business's OWN statement of
 * when it serves — display data for the public page and the JSON-LD
 * {@code openingHours} field (the plan: «وعرضها في openingHours النمطية»).
 *
 * <p><b>Day numbering is ISO (1=Monday..7=Sunday):</b> {@link DayOfWeek#getValue()}
 * — the standard library's own numbering, mapped with zero translation
 * (the V96 CHECK pins the same range at the database level).
 *
 * <p><b>One window per provider per day (the plan's unique key):</b> the
 * partial unique index {@code uq_business_hours_provider_day} enforces
 * provider×day over the live rows (the V70 identity shape). A business
 * with split hours (9–12, 13–18) declares the day as one approximated
 * window or omits it — the plan's allocation is the single-window key,
 * and inventing a second row per day would deviate from it.
 */
@Entity
@Table(name = "business_hours")
@Audited
public class BusinessHour extends BaseEntity {

    @Id
    private UUID id;

    /** The owning provider profile — the page this window renders on. */
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    /**
     * The ISO weekday (1=Monday..7=Sunday) — {@code DayOfWeek.getValue()}
     * mapped verbatim (the V96 CHECK pins BETWEEN 1 AND 7).
     */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    /** The window's opening time of day. */
    @Column(name = "opens_at", nullable = false)
    private LocalTime opensAt;

    /** The window's closing time of day (strictly after the opening — V96 CHECK). */
    @Column(name = "closes_at", nullable = false)
    private LocalTime closesAt;

    protected BusinessHour() {
    }

    private BusinessHour(UUID id, UUID providerId, int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {
        this.id = id;
        this.providerId = providerId;
        this.dayOfWeek = dayOfWeek;
        this.opensAt = opensAt;
        this.closesAt = closesAt;
    }

    /**
     * Factory for the write surface: validates the ISO day range and the
     * window's own order once, at construction — the entity never holds a
     * shape the V96 CHECKs would reject (fail-loud at the boundary, the
     * house rule).
     */
    public static BusinessHour create(UUID providerId, DayOfWeek dayOfWeek,
                                      LocalTime opensAt, LocalTime closesAt) {
        if (dayOfWeek == null || opensAt == null || closesAt == null) {
            throw new IllegalArgumentException("day, opensAt and closesAt are all required");
        }
        if (!opensAt.isBefore(closesAt)) {
            throw new IllegalArgumentException("opensAt must be strictly before closesAt");
        }
        return new BusinessHour(UUID.randomUUID(), providerId, dayOfWeek.getValue(), opensAt, closesAt);
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID getProviderId() {
        return providerId;
    }

    /** The ISO weekday as the standard library's enum — the display and JSON-LD form. */
    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public LocalTime getOpensAt() {
        return opensAt;
    }

    public LocalTime getClosesAt() {
        return closesAt;
    }

    /**
     * The self-service update: the window moves as a whole (PUT
     * replacement semantics — the profile's own documented contract); the
     * same construction-time validation applies.
     */
    public void update(DayOfWeek newDay, LocalTime newOpens, LocalTime newCloses) {
        if (newDay == null || newOpens == null || newCloses == null) {
            throw new IllegalArgumentException("day, opensAt and closesAt are all required");
        }
        if (!newOpens.isBefore(newCloses)) {
            throw new IllegalArgumentException("opensAt must be strictly before closesAt");
        }
        this.dayOfWeek = newDay.getValue();
        this.opensAt = newOpens;
        this.closesAt = newCloses;
    }
}

package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.Instant;
import java.util.UUID;

/**
 * A provider's bookable time window. R2 (comprehensive-review-ar-fix plan
 * §4/R2 — slot ownership): the slot carries {@code heldByBookingId} — the
 * booking whose confirm()/autoConfirm() call claimed it. {@code booked}
 * and the owner are set and cleared together ({@link #markBooked(UUID)}/
 * {@link #markAvailable()}), so a live booked row always tells WHO holds
 * the window and a release that does not carry that booking's id can
 * never free it.
 */
@Entity
@Table(name = "availability_slots")
@Audited
public class AvailabilitySlot extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Column(name = "booked", nullable = false)
    private boolean booked;

    /**
     * R2: the booking that holds this slot — claimed atomically with
     * {@code booked} by {@code bookSlot(providerId, startsAt, endsAt, bookingId)}
     * and cleared with it on release. Nullable in the schema (V72) because
     * ownership accrues from the first post-migration confirm onward;
     * pre-migration holders were backfilled by V72's deterministic UPDATE.
     */
    @Column(name = "held_by_booking_id")
    private UUID heldByBookingId;

    protected AvailabilitySlot() {}

    private AvailabilitySlot(UUID id, UUID providerId, Instant startsAt, Instant endsAt, boolean booked) {
        this.id = id;
        this.providerId = providerId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.booked = booked;
    }

    public static AvailabilitySlot open(UUID providerId, Instant startsAt, Instant endsAt) {
        return new AvailabilitySlot(UUID.randomUUID(), providerId, startsAt, endsAt, false);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public boolean isBooked() { return booked; }
    public UUID getHeldByBookingId() { return heldByBookingId; }

    /**
     * R2: books the slot IN THE NAME of the confirming booking — one
     * claim, booked flag and owner together. Concurrent claims on the
     * same row are settled by the entity's {@code @Version} optimistic
     * lock (BaseEntity — the losing flush fails its transaction), with
     * V73's {@code uq_availability_slots_live_window} as the one-live-row
     * backstop underneath.
     */
    public void markBooked(UUID bookingId) {
        this.booked = true;
        this.heldByBookingId = bookingId;
    }

    /** R2: releases the hold — the flag and the owner clear together. */
    public void markAvailable() {
        this.booked = false;
        this.heldByBookingId = null;
    }
}

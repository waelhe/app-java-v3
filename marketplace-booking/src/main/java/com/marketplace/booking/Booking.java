package com.marketplace.booking;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings")
public class Booking extends BaseEntity {

    @Id
    private UUID id;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "listing_id", nullable = false)
    private UUID listingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status = BookingStatus.PENDING;

    @Column(name = "price_cents", nullable = false)
    private Long priceCents;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "SAR";

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    protected Booking() {
    }

    public Booking(UUID id, UUID consumerId, UUID providerId, UUID listingId,
                   Long priceCents, Instant startsAt, Instant endsAt, String notes) {
        this.id = id;
        this.consumerId = consumerId;
        this.providerId = providerId;
        this.listingId = listingId;
        this.priceCents = priceCents;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.notes = notes;
    }

    public static Booking create(UUID consumerId, UUID providerId, UUID listingId,
                                 Long priceCents, Instant startsAt, Instant endsAt, String notes) {
        return new Booking(UUID.randomUUID(), consumerId, providerId, listingId, priceCents, startsAt, endsAt, notes);
    }

    public static Booking create(UUID consumerId, UUID providerId, UUID listingId,
                                 Long priceCents, String notes) {
        return create(consumerId, providerId, listingId, priceCents, null, null, notes);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getConsumerId() { return consumerId; }
    public UUID getProviderId() { return providerId; }
    public UUID getListingId() { return listingId; }
    public BookingStatus getStatus() { return status; }
    public Long getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getNotes() { return notes; }

    public void confirm() {
        if (this.status != BookingStatus.PENDING) {
            throw new IllegalStateException("Can only confirm PENDING bookings");
        }
        this.status = BookingStatus.CONFIRMED;
    }

    public void complete() {
        if (this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Can only complete CONFIRMED bookings");
        }
        this.status = BookingStatus.COMPLETED;
    }

    public void reject() {
        if (this.status != BookingStatus.PENDING && this.status != BookingStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Can only reject pending bookings");
        }
        this.status = BookingStatus.REJECTED;
    }

    public void requestReschedule(Instant startsAt, Instant endsAt) {
        if (this.status == BookingStatus.COMPLETED || this.status == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot reschedule completed or cancelled bookings");
        }
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = BookingStatus.RESCHEDULE_REQUESTED;
    }

    public void markNoShow() {
        if (this.status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Can only mark confirmed bookings as no-show");
        }
        this.status = BookingStatus.NO_SHOW;
    }

    public void openDispute() {
        if (this.status == BookingStatus.CANCELLED) {
            throw new IllegalStateException("Cannot dispute cancelled bookings");
        }
        this.status = BookingStatus.DISPUTED;
    }

    public void cancel() {
        if (this.status == BookingStatus.COMPLETED) {
            throw new IllegalStateException("Cannot cancel COMPLETED bookings");
        }
        this.status = BookingStatus.CANCELLED;
    }
}
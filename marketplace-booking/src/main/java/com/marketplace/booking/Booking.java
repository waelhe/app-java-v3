package com.marketplace.booking;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "bookings")
@Audited
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

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    protected Booking() {
    }

    public Booking(UUID id, UUID consumerId, UUID providerId, UUID listingId,
                   Long priceCents, String notes) {
        this.id = id;
        this.consumerId = consumerId;
        this.providerId = providerId;
        this.listingId = listingId;
        this.priceCents = priceCents;
        this.notes = notes;
    }

    public static Booking create(UUID consumerId, UUID providerId, UUID listingId,
                                 Long priceCents, String notes) {
        return new Booking(UUID.randomUUID(), consumerId, providerId, listingId, priceCents, notes);
    }

    @Override
    public UUID getId() { return id; }
    public UUID getConsumerId() { return consumerId; }
    public UUID getProviderId() { return providerId; }
    public UUID getListingId() { return listingId; }
    public BookingStatus getStatus() { return status; }
    public Long getPriceCents() { return priceCents; }
    public String getCurrency() { return currency; }
    public String getNotes() { return notes; }

    public void confirm() {
        this.status.validateTransitionTo(BookingStatus.CONFIRMED);
        this.status = BookingStatus.CONFIRMED;
    }

    public void complete() {
        this.status.validateTransitionTo(BookingStatus.COMPLETED);
        this.status = BookingStatus.COMPLETED;
    }

    public void cancel() {
        this.status.validateTransitionTo(BookingStatus.CANCELLED);
        this.status = BookingStatus.CANCELLED;
    }
}
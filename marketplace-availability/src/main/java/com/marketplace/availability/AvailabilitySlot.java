package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "availability_slots")
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
}

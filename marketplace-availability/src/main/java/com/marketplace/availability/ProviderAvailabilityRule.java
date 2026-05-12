package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "provider_availability_rules")
public class ProviderAvailabilityRule extends BaseEntity {
    @Id
    private UUID id;
    @Column(name = "provider_id", nullable = false)
    private UUID providerId;
    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;
    @Column(name = "starts_at", nullable = false)
    private LocalTime startsAt;
    @Column(name = "ends_at", nullable = false)
    private LocalTime endsAt;
    @Column(name = "slot_minutes", nullable = false)
    private Integer slotMinutes;

    protected ProviderAvailabilityRule() {}

    public ProviderAvailabilityRule(UUID id, UUID providerId, Integer dayOfWeek, LocalTime startsAt, LocalTime endsAt, Integer slotMinutes) {
        this.id = id;
        this.providerId = providerId;
        this.dayOfWeek = dayOfWeek;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.slotMinutes = slotMinutes;
    }

    public static ProviderAvailabilityRule create(UUID providerId, Integer dayOfWeek, LocalTime startsAt, LocalTime endsAt, Integer slotMinutes) {
        return new ProviderAvailabilityRule(UUID.randomUUID(), providerId, dayOfWeek, startsAt, endsAt, slotMinutes);
    }

    @Override public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public Integer getDayOfWeek() { return dayOfWeek; }
    public LocalTime getStartsAt() { return startsAt; }
    public LocalTime getEndsAt() { return endsAt; }
    public Integer getSlotMinutes() { return slotMinutes; }
}

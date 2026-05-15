package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;

@Entity
@Table(name = "provider_availability_rules")
@Audited
public class ProviderAvailabilityRule extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 20)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    protected ProviderAvailabilityRule() {}

    private ProviderAvailabilityRule(UUID id, UUID providerId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        this.id = id;
        this.providerId = providerId;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public static ProviderAvailabilityRule create(UUID providerId, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime) {
        return new ProviderAvailabilityRule(UUID.randomUUID(), providerId, dayOfWeek, startTime, endTime);
    }

    @Override
    public UUID getId() { return id; }
}

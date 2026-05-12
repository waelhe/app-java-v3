package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_time_off")
public class ProviderTimeOff extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    protected ProviderTimeOff() {}

    private ProviderTimeOff(UUID id, UUID providerId, Instant startsAt, Instant endsAt) {
        this.id = id;
        this.providerId = providerId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    public static ProviderTimeOff create(UUID providerId, Instant startsAt, Instant endsAt) {
        return new ProviderTimeOff(UUID.randomUUID(), providerId, startsAt, endsAt);
    }

    @Override
    public UUID getId() { return id; }
}

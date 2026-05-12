package com.marketplace.availability;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "provider_time_off")
public class ProviderTimeOff extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider_id", nullable = false) private UUID providerId;
    @Column(name = "starts_at", nullable = false) private Instant startsAt;
    @Column(name = "ends_at", nullable = false) private Instant endsAt;
    @Column(name = "reason", length = 500) private String reason;

    protected ProviderTimeOff() {}
    public ProviderTimeOff(UUID id, UUID providerId, Instant startsAt, Instant endsAt, String reason) {
        this.id = id; this.providerId = providerId; this.startsAt = startsAt; this.endsAt = endsAt; this.reason = reason;
    }
    public static ProviderTimeOff create(UUID providerId, Instant startsAt, Instant endsAt, String reason) {
        return new ProviderTimeOff(UUID.randomUUID(), providerId, startsAt, endsAt, reason);
    }
    @Override public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public String getReason() { return reason; }
}

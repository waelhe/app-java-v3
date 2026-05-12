package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "payouts")
public class Payout extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider_id", nullable = false) private UUID providerId;
    @Column(name = "amount_cents", nullable = false) private Long amountCents;
    @Column(name = "currency", nullable = false, length = 3) private String currency = "SAR";
    @Column(name = "status", nullable = false, length = 20) private String status = "REQUESTED";
    protected Payout() {}
    public Payout(UUID id, UUID providerId, Long amountCents) { this.id = id; this.providerId = providerId; this.amountCents = amountCents; }
    public static Payout create(UUID providerId, Long amountCents) { return new Payout(UUID.randomUUID(), providerId, amountCents); }
    @Override public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
}

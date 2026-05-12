package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "provider_balances")
public class ProviderBalance extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider_id", nullable = false, unique = true) private UUID providerId;
    @Column(name = "available_cents", nullable = false) private Long availableCents;
    @Column(name = "currency", nullable = false, length = 3) private String currency = "SAR";
    protected ProviderBalance() {}
    public ProviderBalance(UUID id, UUID providerId, Long availableCents) { this.id = id; this.providerId = providerId; this.availableCents = availableCents; }
    public static ProviderBalance create(UUID providerId) { return new ProviderBalance(UUID.randomUUID(), providerId, 0L); }
    @Override public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public Long getAvailableCents() { return availableCents; }
    public String getCurrency() { return currency; }
    public void apply(Long amountCents) { this.availableCents += amountCents; }
}

package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "provider_balances")
@Audited
public class ProviderBalance extends BaseEntity {
    @Id
    private UUID providerId;

    @Column(name = "available_cents", nullable = false)
    private long availableCents;

    protected ProviderBalance() {}

    private ProviderBalance(UUID providerId, long availableCents) {
        this.providerId = providerId;
        this.availableCents = availableCents;
    }

    public static ProviderBalance empty(UUID providerId) { return new ProviderBalance(providerId, 0); }

    @Override public UUID getId(){return providerId;}
    public long getAvailableCents(){return availableCents;}
    public void credit(long amountCents){ this.availableCents += amountCents; }
}

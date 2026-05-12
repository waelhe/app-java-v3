package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "settlements")
public class Settlement extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider_id", nullable = false) private UUID providerId;
    @Column(name = "amount_cents", nullable = false) private Long amountCents;
    @Column(name = "status", nullable = false, length = 20) private String status = "OPEN";
    protected Settlement() {}
    public Settlement(UUID id, UUID providerId, Long amountCents) { this.id = id; this.providerId = providerId; this.amountCents = amountCents; }
    @Override public UUID getId() { return id; }
}

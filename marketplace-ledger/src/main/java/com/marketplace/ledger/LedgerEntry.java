package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry extends BaseEntity {
    @Id private UUID id;
    @Column(name = "provider_id") private UUID providerId;
    @Column(name = "source_id", nullable = false) private UUID sourceId;
    @Enumerated(EnumType.STRING) @Column(name = "type", nullable = false, length = 32) private LedgerEntryType type;
    @Column(name = "amount_cents", nullable = false) private Long amountCents;
    @Column(name = "currency", nullable = false, length = 3) private String currency = "SAR";

    protected LedgerEntry() {}
    public LedgerEntry(UUID id, UUID providerId, UUID sourceId, LedgerEntryType type, Long amountCents) {
        this.id = id; this.providerId = providerId; this.sourceId = sourceId; this.type = type; this.amountCents = amountCents;
    }
    public static LedgerEntry create(UUID providerId, UUID sourceId, LedgerEntryType type, Long amountCents) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, type, amountCents);
    }
    @Override public UUID getId() { return id; }
    public UUID getProviderId() { return providerId; }
    public UUID getSourceId() { return sourceId; }
    public LedgerEntryType getType() { return type; }
    public Long getAmountCents() { return amountCents; }
    public String getCurrency() { return currency; }
}

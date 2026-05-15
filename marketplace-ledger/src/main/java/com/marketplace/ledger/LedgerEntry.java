package com.marketplace.ledger;

import com.marketplace.shared.jpa.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.envers.Audited;

import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
@Audited
public class LedgerEntry extends BaseEntity {
    @Id
    private UUID id;

    @Column(name = "provider_id", nullable = false)
    private UUID providerId;

    @Column(name = "source_id", nullable = false, unique = true)
    private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 30)
    private LedgerEntryType entryType;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    protected LedgerEntry() {}

    private LedgerEntry(UUID id, UUID providerId, UUID sourceId, LedgerEntryType entryType, long amountCents) {
        this.id = id;
        this.providerId = providerId;
        this.sourceId = sourceId;
        this.entryType = entryType;
        this.amountCents = amountCents;
    }

    public static LedgerEntry paymentCredit(UUID providerId, UUID sourceId, long amountCents) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, LedgerEntryType.PAYMENT_CREDIT, amountCents);
    }

    @Override public UUID getId(){return id;}
}

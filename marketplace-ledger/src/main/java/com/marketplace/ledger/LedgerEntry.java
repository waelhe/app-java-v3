package com.marketplace.ledger;

import com.marketplace.shared.api.Currencies;
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

    /**
     * R9 (comprehensive-review-ar-fix plan §4/R9): the entry's ISO 4217
     * currency — the money's own denomination, carried from the booking the
     * payment collected (the listener passes {@code bookingInfo.currency()};
     * the pre-fix listener ignored the field it already had). V75 backfilled
     * the column from each entry's payment intent — the same derivation the
     * runtime path reads — with the house default for unresolvable rows.
     */
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = Currencies.DEFAULT_CODE;

    protected LedgerEntry() {}

    private LedgerEntry(UUID id, UUID providerId, UUID sourceId, LedgerEntryType entryType,
                        long amountCents, String currency) {
        this.id = id;
        this.providerId = providerId;
        this.sourceId = sourceId;
        this.entryType = entryType;
        this.amountCents = amountCents;
        this.currency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
    }

    /** L24 money path 1 of 3 — the credit a completed payment writes, in the payment's currency. */
    public static LedgerEntry paymentCredit(UUID providerId, UUID sourceId, long amountCents, String currency) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, LedgerEntryType.PAYMENT_CREDIT,
                amountCents, currency);
    }

    /** L24 money path 2 of 3 — the commission debit, in the same payment's currency. */
    public static LedgerEntry commissionDebit(UUID providerId, UUID sourceId, long amountCents, String currency) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, LedgerEntryType.COMMISSION_DEBIT,
                amountCents, currency);
    }

    /** L24 — the full refund's debit (mirrors the PAYMENT_CREDIT amount), in the original currency. */
    public static LedgerEntry refundDebit(UUID providerId, UUID sourceId, long amountCents, String currency) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, LedgerEntryType.REFUND_DEBIT,
                amountCents, currency);
    }

    /**
     * W5 (yelp-level plan §5 — G24): the ad bill's debit — the frozen
     * window charge, in the campaign's own currency. The source id is
     * the DETERMINISTIC window key the service derives
     * ({@code UUID.nameUUIDFromBytes("AD_DEBIT:{campaignId}:{windowStart}")})
     * — the same JDK v3 derivation V82 documented for the commission and
     * refund prefixes — so the V19 {@code source_id UNIQUE} index rejects
     * any replay of the same window structurally.
     */
    public static LedgerEntry adDebit(UUID providerId, UUID sourceId, long amountCents, String currency) {
        return new LedgerEntry(UUID.randomUUID(), providerId, sourceId, LedgerEntryType.AD_DEBIT,
                amountCents, currency);
    }

    @Override public UUID getId(){return id;}
    public UUID getSourceId(){return sourceId;}
    public LedgerEntryType getEntryType(){return entryType;}
    public long getAmountCents(){return amountCents;}
    public String getCurrency(){return currency;}
}

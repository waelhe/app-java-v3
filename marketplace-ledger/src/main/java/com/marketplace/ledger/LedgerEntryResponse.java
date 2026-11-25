package com.marketplace.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement of the provider statement (L20): the ledger entry as the
 * owning provider reads it — entry type, signed amount and the instant it
 * landed, plus the source id that links the payment credit and its
 * matching commission debit back to the same payment intent. The ledger
 * stores every amount as a magnitude; the sign is presentation, and
 * {@link #from} keeps it consistent with the amounts it displays:
 * <b>credits render positive</b> — {@code PAYMENT_CREDIT} and the full
 * refund's {@code COMMISSION_REVERSAL_CREDIT} (CodeRabbit round 3 — both
 * book through {@code balance.credit}) and {@code DELIVERY_FEE_REVERSAL_CREDIT}
 * (F-832-1 — the refund's fee mirror, the same {@code balance.credit} leg) —
 * while <b>debits render negative</b> — {@code COMMISSION_DEBIT},
 * {@code DELIVERY_FEE_DEBIT}, {@code REFUND_DEBIT} and {@code AD_DEBIT} —
 * so a client summing the page reproduces the balance exactly (the L20
 * statement contract).
 *
 * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9):</b> each movement
 * carries its ISO 4217 currency — a client summing the page reproduces the
 * balance PER CURRENCY (summing across currencies was the defect).</p>
 */
public record LedgerEntryResponse(
        UUID id,
        UUID sourceId,
        String entryType,
        long amountCents,
        String currency,
        Instant createdAt
) {
    static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getSourceId(),
                entry.getEntryType().name(),
                entry.getEntryType() == LedgerEntryType.PAYMENT_CREDIT
                        || entry.getEntryType() == LedgerEntryType.COMMISSION_REVERSAL_CREDIT
                        || entry.getEntryType() == LedgerEntryType.DELIVERY_FEE_REVERSAL_CREDIT
                        ? entry.getAmountCents()
                        : -entry.getAmountCents(),
                entry.getCurrency(),
                entry.getCreatedAt()
        );
    }
}

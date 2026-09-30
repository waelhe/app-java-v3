package com.marketplace.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement of the provider statement (L20): the ledger entry as the
 * owning provider reads it — entry type, signed amount (credits positive,
 * debits negative — commission AND refund — so a client summing the page
 * reproduces the balance) and the instant it landed, plus the source id
 * that links the payment credit and its matching commission debit back to
 * the same payment intent. The ledger stores the debit magnitude; the sign
 * is presentation.
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
                        ? entry.getAmountCents()
                        : -entry.getAmountCents(),
                entry.getCurrency(),
                entry.getCreatedAt()
        );
    }
}

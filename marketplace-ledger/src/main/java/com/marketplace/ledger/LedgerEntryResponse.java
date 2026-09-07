package com.marketplace.ledger;

import java.time.Instant;
import java.util.UUID;

/**
 * One movement of the provider statement (L20): the ledger entry as the
 * owning provider reads it — entry type, signed amount and the instant it
 * landed, plus the source id that links the payment credit and its matching
 * commission debit back to the same payment intent.
 */
public record LedgerEntryResponse(
        UUID id,
        UUID sourceId,
        String entryType,
        long amountCents,
        Instant createdAt
) {
    static LedgerEntryResponse from(LedgerEntry entry) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getSourceId(),
                entry.getEntryType().name(),
                entry.getAmountCents(),
                entry.getCreatedAt()
        );
    }
}

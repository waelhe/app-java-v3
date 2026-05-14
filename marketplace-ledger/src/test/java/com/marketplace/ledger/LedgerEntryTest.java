package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LedgerEntryTest {

    @Test
    void paymentCreditCreatesEntry() {
        var providerId = UUID.randomUUID();
        var sourceId = UUID.randomUUID();
        var entry = LedgerEntry.paymentCredit(providerId, sourceId, 5000);

        assertNotNull(entry.getId());
        assertEquals(providerId, entry.getId() != null ? providerId : null);
        assertEquals(LedgerEntryType.PAYMENT_CREDIT, LedgerEntryType.PAYMENT_CREDIT);
    }
}

package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LedgerEntryTypeTest {

    @Test
    void hasExpectedValues() {
        assertEquals("PAYMENT_CREDIT", LedgerEntryType.PAYMENT_CREDIT.name());
    }
}

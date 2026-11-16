package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LedgerEntryTypeTest {

    @Test
    void hasExpectedValues() {
        assertEquals("PAYMENT_CREDIT", LedgerEntryType.PAYMENT_CREDIT.name());
        assertEquals("COMMISSION_DEBIT", LedgerEntryType.COMMISSION_DEBIT.name());
        assertEquals("REFUND_DEBIT", LedgerEntryType.REFUND_DEBIT.name());
        assertEquals("AD_DEBIT", LedgerEntryType.AD_DEBIT.name());
        // A2 (owner-gate batch 6a — ADR-0013 D3 §3): the commission
        // reversal mirror joins the closed set (the database's own
        // chk_ledger_entries_entry_type widened by V296/V297 to match).
        assertEquals("COMMISSION_REVERSAL_CREDIT", LedgerEntryType.COMMISSION_REVERSAL_CREDIT.name());
    }
}

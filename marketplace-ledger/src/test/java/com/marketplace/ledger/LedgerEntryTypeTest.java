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
        // A1 (owner-gate batch 6a — ADR-0013 D4): the platform-delivery fee
        // debit joins the closed set (V299/V300 widened the CHECK to match).
        assertEquals("DELIVERY_FEE_DEBIT", LedgerEntryType.DELIVERY_FEE_DEBIT.name());
        // F-832-1 (the 2026-11-14 adversarial behavioral review, P1): the
        // full refund's fee-reversal mirror joins the closed set (the V301/V302
        // pair widens the CHECK to match — the sibling pairs' own shape).
        assertEquals("DELIVERY_FEE_REVERSAL_CREDIT", LedgerEntryType.DELIVERY_FEE_REVERSAL_CREDIT.name());
    }
}

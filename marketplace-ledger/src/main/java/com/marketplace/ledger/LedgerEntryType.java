package com.marketplace.ledger;

public enum LedgerEntryType {
    PAYMENT_CREDIT,
    COMMISSION_DEBIT,
    /** L24 — the full refund's debit mirrors the original PAYMENT_CREDIT. */
    REFUND_DEBIT
}

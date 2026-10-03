package com.marketplace.ledger;

public enum LedgerEntryType {
    PAYMENT_CREDIT,
    COMMISSION_DEBIT,
    /** L24 — the full refund's debit mirrors the original PAYMENT_CREDIT. */
    REFUND_DEBIT,
    /**
     * W5 (yelp-level plan §5 — the ads & billing wave, G24): the ad
     * bill's debit — the frozen window charge that consumes the
     * campaign's budget. A closed-set member like its siblings: the
     * database now pins the whole vocabulary through
     * {@code chk_ledger_entries_entry_type} (V103/V104 — the §7 hard
     * rule: «نوع القيد تعداد Java مُغلق، لا صفًا في جدول إعدادات»).
     */
    AD_DEBIT
}

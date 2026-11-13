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
    AD_DEBIT,
    /**
     * A2 (adversarial review 2026-11-10, owner-gate batch 6a — the
     * ADR-0013 D3 §3 mirror rule): the full refund's commission
     * REVERSAL credit — the mirror image of the settlement's
     * COMMISSION_DEBIT, posted in the SAME refund transaction the
     * debitFromRefund mirror runs in. The ADR's own words: «a full
     * REFUND reverses the credit and its commission mirror» — the
     * pre-fix code executed only the credit reversal, so every full
     * refund left the provider's balance negative by exactly the
     * commission (the ledger's own finding A2). The database's closed
     * set widens with this member through the V296/V297 pair (the
     * V103/V104 + V290/V291 house shape: DROP/ADD NOT VALID, then the
     * VALIDATE under its own SHARE UPDATE EXCLUSIVE scan).
     */
    COMMISSION_REVERSAL_CREDIT
}

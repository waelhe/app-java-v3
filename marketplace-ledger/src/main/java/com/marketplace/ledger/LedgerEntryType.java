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
    COMMISSION_REVERSAL_CREDIT,
    /**
     * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the
     * ADR-0013 D4 execution): the platform-delivery fee debited from the
     * seller's balance at the ORDER settlement — the fee the checkout
     * froze inside the order total and the ONE intent collected. Per
     * D1/D4 the platform is the merchant of record and the fee is the
     * platform's own charge funding the carrier: the seller's allocation
     * is the goods (+tax) value NET of the fee, and the commission
     * computes on the goods base («رسم واحد لمالك واحد» — the pre-fix
     * settlement inflated the seller's owed balance by the fee and
     * computed the commission on it: the ledger's own finding A1). The
     * disclosure discipline is the commission debit's own (D2: the
     * ledger's debit entries are the per-transaction disclosure of
     * record — the seller's statement reads them). The database's
     * closed set widens with this member through the V298/V299 pair
     * (the same V103/V104 + V290/V291 house shape: DROP/ADD NOT VALID,
     * then the VALIDATE under its own SHARE UPDATE EXCLUSIVE scan).
     */
    DELIVERY_FEE_DEBIT
}

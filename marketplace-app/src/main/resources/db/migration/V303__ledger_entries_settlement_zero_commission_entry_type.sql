-- Major-2 (CodeRabbit round 8 on the D4 modeling branch — the
-- 2026-11-20T17:53Z review, Data Integrity): the refund gate must never
-- infer a settlement's HISTORICAL commission decision from the LIVE
-- configuration rate. A legacy pre-V220 row (commission_rate NULL)
-- settled while the live rate was zero writes its PAYMENT_CREDIT with
-- NO commission row (the zero-amount rule); a later rate change made
-- the gate's old commissionNotDue compute a DUE commission from TODAY'S
-- rate and reject the refund forever — the review's measured
-- eternal-rejection reproducer (17400 goods, rate 0 -> 0.10, refund
-- blocked on every retry with no REFUND_DEBIT to repair it).
--
-- The fix: the settlement transaction itself now records its OWN
-- zero-commission decision of record — a NON-money marker row
-- (SETTLEMENT_ZERO_COMMISSION, amount 0, no balance movement) written
-- in the SAME transaction that creates the PAYMENT_CREDIT row, keyed by
-- the deterministic "settlement-zero-commission-<intentId>" source id.
-- The refund gate reads the marker for legacy null-rate rows (never the
-- live rate); the manual credit path cannot forge it (only the
-- settlement listener writes it). Pre-marker legacy rows carry no
-- decision of record — their refunds stay fail-closed, the documented
-- owner-gate residual (an unknowable historical decision is never
-- reconstructed by a live inference).
--
-- This migration widens the closed-set CHECK the W5 wave pinned
-- (V103/V104 — the §7 hard rule: «نوع القيد تعداد Java مُغلق، لا صفًا
-- في جدول إعدادات») with the new Java enum member
-- SETTLEMENT_ZERO_COMMISSION. The enum stays the authority; the CHECK
-- is the backstop (the same discipline that governed V44, V56, V103).
--
-- The route is the house precedent verbatim (V187's own header
-- prescribed it; V290 executed it for
-- user_role_assignments_source_check; the A2 sibling took it for
-- COMMISSION_REVERSAL_CREDIT as V296/V297, the A1 wave for
-- DELIVERY_FEE_DEBIT as V299/V300, and F-832-1 for
-- DELIVERY_FEE_REVERSAL_CREDIT as V301/V302 — the numbering rides ABOVE
-- the siblings' pairs per the B1 shared-number-space discipline: gaps
-- are benign, renumbering is not; the pair merges in creation order).
--
-- The eight-value set carries the SUPERSET of the in-flight waves: the
-- constraint stays the union under any merge order — a superset is
-- harmless (no runtime writes a value the enum lacks; the reverse (a
-- subset that drops a live member under written rows) is the hazard
-- this avoids).
--
-- DROP + ADD (not a value-list ALTER): PostgreSQL replaces a CHECK by
-- dropping and re-adding it; the NOT VALID half lands here, the
-- VALIDATE half in V304 under its own SHARE UPDATE EXCLUSIVE scan (the
-- V68/V69 + V87 measured lesson).

ALTER TABLE ledger_entries DROP CONSTRAINT chk_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_entry_type
    CHECK (entry_type IN ('PAYMENT_CREDIT', 'COMMISSION_DEBIT', 'REFUND_DEBIT', 'AD_DEBIT',
        'COMMISSION_REVERSAL_CREDIT', 'DELIVERY_FEE_DEBIT', 'DELIVERY_FEE_REVERSAL_CREDIT',
        'SETTLEMENT_ZERO_COMMISSION')) NOT VALID;

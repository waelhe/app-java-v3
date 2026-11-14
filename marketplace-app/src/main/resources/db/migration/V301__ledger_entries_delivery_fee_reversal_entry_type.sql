-- F-832-1 (the 2026-11-14 adversarial behavioral review, P1 — the
-- ADR-0013 D4 mirror rule the settlement's own fee leg owes): the full
-- refund of a platform-delivery order must reverse the fee debit the
-- settlement posted. The pre-fix refund reversed the goods credit and
-- the commission debit but NOT the DELIVERY_FEE_DEBIT, so every full
-- refund left the seller's balance negative by exactly the fee — the
-- buyer got his fee back while the seller kept paying for it (the
-- review's measured −fee-on-refund journey, row-registered in the
-- governing review ledger).
--
-- This migration widens the closed-set CHECK the W5 wave pinned
-- (V103/V104 — the §7 hard rule: «نوع القيد تعداد Java مُغلق، لا صفًا
-- في جدول إعدادات») with the new Java enum member
-- DELIVERY_FEE_REVERSAL_CREDIT. The enum stays the authority; the CHECK
-- is the backstop (the same discipline that governed V44, V56, V103).
--
-- The route is the house precedent verbatim (V187's own header
-- prescribed it; V290 executed it for
-- user_role_assignments_source_check; the A2 sibling took it for
-- COMMISSION_REVERSAL_CREDIT as V296/V297 and the A1 wave for
-- DELIVERY_FEE_DEBIT as V299/V300 — the numbering rides ABOVE the
-- siblings' pairs per the B1 shared-number-space discipline: gaps are
-- benign, renumbering is not; the pair merges in creation order).
--
-- The seven-value set carries the SUPERSET of the in-flight waves (A2's
-- COMMISSION_REVERSAL_CREDIT + A1's DELIVERY_FEE_DEBIT + this wave's
-- DELIVERY_FEE_REVERSAL_CREDIT): the constraint stays the union under
-- any merge order — a superset is harmless (no runtime writes a value
-- the enum lacks; the reverse (a subset that drops a live member under
-- written rows) is the hazard this avoids).
--
-- DROP + ADD (not a value-list ALTER): PostgreSQL replaces a CHECK by
-- dropping and re-adding it; the NOT VALID half lands here, the VALIDATE
-- half in V302 under its own SHARE UPDATE EXCLUSIVE scan (the V68/V69 +
-- V87 measured lesson).

ALTER TABLE ledger_entries DROP CONSTRAINT chk_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_entry_type
    CHECK (entry_type IN ('PAYMENT_CREDIT', 'COMMISSION_DEBIT', 'REFUND_DEBIT', 'AD_DEBIT',
        'COMMISSION_REVERSAL_CREDIT', 'DELIVERY_FEE_DEBIT', 'DELIVERY_FEE_REVERSAL_CREDIT')) NOT VALID;

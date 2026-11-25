-- A1 (the 2026-11-10 adversarial review's owner-gate batch 6a — the
-- ADR-0013 D4 execution, by the owner's standing order adopting the
-- documented owner-gate recommendations): the ORDER settlement's
-- delivery-fee modeling. The checkout froze the platform-delivery fee
-- inside the order total and the ONE intent collected it; per D1/D4 the
-- platform is the merchant of record and the fee is the platform's own
-- charge funding the carrier — so the settlement debits the fee from
-- the seller's balance (DELIVERY_FEE_DEBIT, the commission debit's own
-- disclosure discipline) and computes the commission on the goods base
-- («رسم واحد لمالك واحد»). The pre-fix settlement credited the seller
-- the FULL amount including the fee and computed the commission on it
-- (the ledger's own finding A1, row-registered in the governing ledger
-- #816).
--
-- This migration widens the closed-set CHECK the W5 wave pinned
-- (V103/V104) with the new Java enum member DELIVERY_FEE_DEBIT. The
-- enum stays the authority; the CHECK the backstop (the discipline that
-- governed V44, V56, V103).
--
-- The route is the house precedent verbatim (V187's own header
-- prescribed it; V290 executed it for user_role_assignments_source_check;
-- the A2 sibling took it for COMMISSION_REVERSAL_CREDIT as V296/V297 —
-- the numbering rides ABOVE the sibling's unmerged pair per the B1
-- shared-number-space discipline: gaps are benign, renumbering is not;
-- the pair merges in creation order).
--
-- The six-value set carries the SUPERSET of the two in-flight waves
-- (A2's COMMISSION_REVERSAL_CREDIT + A1's DELIVERY_FEE_DEBIT): the
-- constraint stays the union under either merge order — a superset is
-- harmless (no runtime writes a value the enum lacks; the reverse
-- (a subset that drops a live member under written rows) is the hazard
-- this avoids).
--
-- DROP + ADD (not a value-list ALTER): PostgreSQL replaces a CHECK by
-- dropping and re-adding it; the NOT VALID half lands here, the VALIDATE
-- half in V299 under its own SHARE UPDATE EXCLUSIVE scan (the V68/V69 +
-- V87 measured lesson).

ALTER TABLE ledger_entries DROP CONSTRAINT chk_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_entry_type
    CHECK (entry_type IN ('PAYMENT_CREDIT', 'COMMISSION_DEBIT', 'REFUND_DEBIT', 'AD_DEBIT',
        'COMMISSION_REVERSAL_CREDIT', 'DELIVERY_FEE_DEBIT')) NOT VALID;

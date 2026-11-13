-- A2 (the 2026-11-10 adversarial review's owner-gate batch 6a — the
-- ADR-0013 D3 §3 mirror rule, executed by the owner's standing order
-- «افعل التوصيات الرسمية»): the full refund must reverse the credit AND
-- its commission mirror — the ADR's own words: «a full REFUND reverses
-- the credit and its commission mirror». The pre-fix code executed only
-- the credit reversal, so every full refund on the three
-- commission-carrying origins (ORDER / BOOKING / LOAN) over-debited the
-- provider by exactly the posted commission (the ledger's own finding
-- A2, row-registered in the governing review ledger #816).
--
-- This migration widens the closed-set CHECK the W5 wave pinned
-- (V103/V104 — the §7 hard rule: «نوع القيد تعداد Java مُغلق، لا صفًا
-- في جدول إعدادات») with the new Java enum member
-- COMMISSION_REVERSAL_CREDIT. The enum stays the authority; the CHECK
-- is the backstop (the same discipline that governed V44, V56, V103).
--
-- The route is the house precedent verbatim: V187's own header
-- prescribed it («A future source widens the CHECK through the V68
-- NOT VALID → VALIDATE split») and V290 executed exactly this shape for
-- user_role_assignments_source_check. ADD CONSTRAINT ... NOT VALID holds
-- its ACCESS EXCLUSIVE lock only until THIS transaction commits, so the
-- validation scan runs in its OWN migration (V297) under SHARE UPDATE
-- EXCLUSIVE — ordinary reads and writes keep flowing during the scan.
--
-- DROP + ADD (not a value-list ALTER): PostgreSQL replaces a CHECK by
-- dropping and re-adding it; the NOT VALID half lands here, the VALIDATE
-- half in V297 (validating an already-valid constraint is a no-op —
-- re-runnable after repair, the V57 letter).
--
-- The five-value set validates clean by construction: the live table
-- holds only the four pre-A2 members — COMMISSION_REVERSAL_CREDIT
-- enters with this wave's runtime alone.

ALTER TABLE ledger_entries DROP CONSTRAINT chk_ledger_entries_entry_type;
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_entry_type
    CHECK (entry_type IN ('PAYMENT_CREDIT', 'COMMISSION_DEBIT', 'REFUND_DEBIT', 'AD_DEBIT',
        'COMMISSION_REVERSAL_CREDIT')) NOT VALID;

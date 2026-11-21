-- A1 (owner-gate batch 6a — the ADR-0013 D4 execution): the VALIDATE half
-- of the V298 pair. Every CHECK lands NOT VALID (the V44 shape); the
-- VALIDATE runs under its OWN statement's SHARE UPDATE EXCLUSIVE alone —
-- the V68/V69 + V87 measured lesson (never share a transaction with the
-- DROP/ADD that preceded it: that scan would run under ACCESS EXCLUSIVE
-- and block live traffic).
--
-- Validates clean by construction: the live ledger_entries rows carry
-- only the pre-wave members of the closed set — the new members
-- (COMMISSION_REVERSAL_CREDIT from the A2 sibling, DELIVERY_FEE_DEBIT
-- from this wave) only enter with their runtimes.

ALTER TABLE ledger_entries VALIDATE CONSTRAINT chk_ledger_entries_entry_type;

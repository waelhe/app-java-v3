-- A2 (owner-gate batch 6a — the ADR-0013 D3 §3 mirror rule): the
-- VALIDATE half of the V296 pair. Every CHECK lands NOT VALID (the V44
-- shape); the VALIDATE runs under its OWN statement's SHARE UPDATE
-- EXCLUSIVE alone — the V68/V69 + V87 measured lesson (never share a
-- transaction with the DROP/ADD that preceded it: that scan would run
-- under ACCESS EXCLUSIVE and block live traffic).
--
-- Validates clean by construction: the live ledger_entries rows carry
-- only the four pre-A2 members of the closed set — the fifth
-- (COMMISSION_REVERSAL_CREDIT) only enters with this wave's runtime.

ALTER TABLE ledger_entries VALIDATE CONSTRAINT chk_ledger_entries_entry_type;

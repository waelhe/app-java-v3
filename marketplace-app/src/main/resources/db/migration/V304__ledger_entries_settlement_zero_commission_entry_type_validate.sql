-- Major-2 (CodeRabbit round 8 on the D4 modeling branch — the
-- 2026-11-20T17:53Z review, Data Integrity): the VALIDATE half of the
-- V303 pair. Every CHECK lands NOT VALID (the V44 shape); the VALIDATE
-- runs under its OWN statement's SHARE UPDATE EXCLUSIVE alone — the
-- V68/V69 + V87 measured lesson (never share a transaction with the
-- DROP/ADD that preceded it: that scan would run under ACCESS EXCLUSIVE
-- and block live traffic).
--
-- Validates clean by construction: the live ledger_entries rows carry
-- only the pre-wave members of the closed set — the new member
-- (SETTLEMENT_ZERO_COMMISSION) only enters with this wave's runtime.

ALTER TABLE ledger_entries VALIDATE CONSTRAINT chk_ledger_entries_entry_type;

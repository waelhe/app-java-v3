-- F-832-1 (the 2026-11-14 adversarial behavioral review, P1): the
-- VALIDATE half of the V301 pair. Every CHECK lands NOT VALID (the V44
-- shape); the VALIDATE runs under its OWN statement's SHARE UPDATE
-- EXCLUSIVE alone — the V68/V69 + V87 measured lesson (never share a
-- transaction with the DROP/ADD that preceded it: that scan would run
-- under ACCESS EXCLUSIVE and block live traffic).
--
-- Validates clean by construction: the live ledger_entries rows carry
-- only the pre-wave members of the closed set — the new member
-- (DELIVERY_FEE_REVERSAL_CREDIT) only enters with this wave's runtime.

ALTER TABLE ledger_entries VALIDATE CONSTRAINT chk_ledger_entries_entry_type;

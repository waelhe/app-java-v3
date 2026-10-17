-- ADR-0013 (D2 — the commission freeze): the VALIDATE half of the pair.
-- The CHECK landed NOT VALID in V220 (the V103/V104 discipline); this leg
-- runs under its OWN statement's SHARE UPDATE EXCLUSIVE alone — the
-- V68/V69 + V87 measured lesson (never share a transaction with the ADD
-- that preceded it: that scan would run under ACCESS EXCLUSIVE and block
-- the live payment surfaces).
--
-- Every existing row validates clean by construction: the column arrived
-- NULL in V220 and only the post-freeze runtime writes non-NULL values —
-- every one of them inside [0, 1] by the entity's own factory contract
-- (the CommissionProperties binding rejects anything outside the range at
-- startup, so the runtime cannot even produce a violating row).

ALTER TABLE payment_intents VALIDATE CONSTRAINT chk_payment_intents_commission_rate;

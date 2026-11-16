-- ADR-0013 (D2 — the commission freeze): the announced commission rate
-- freezes onto each transaction when the payment intent is created —
-- Stripe's application-fee model verbatim: the fee the parties agreed to
-- is fixed at charge creation and never recomputed at settlement. The
-- measured gap this closes: the ledger's listener read the rate from
-- configuration at ITS construction and computed at event-processing
-- time, so a rate change between placement and settlement would rewrite
-- the posted fee (the audit's own finding, recorded in the ADR).
--
-- Shape decisions (each grounded):
--   * NULLABLE, deliberately: the column is the freeze of the FUTURE —
--     every intent the runtime creates after this migration carries the
--     announced rate (the entity's factories stamp it); the pre-freeze
--     rows keep NULL because their creation-time rate is UNKNOWABLE after
--     the fact (a DEFAULT 0.10 would lie about history — the same reason
--     V82 rejected a currency DEFAULT: it converts an honest unknown into
--     a silent wrong value). The ledger reads NULL as "legacy intent" and
--     falls back to the live configuration — the ADR's own interim rule
--     («do not change the rate while intents are open» becomes structural:
--     only NULL-rate rows depend on the live value).
--   * NUMERIC(5,4): a rate in [0, 1) with four decimal places — 0.1000 is
--     the measured default; the ledger's arithmetic stays BigDecimal
--     end-to-end (the R9 money discipline: no floating drift between the
--     frozen value and the posted one).
--   * CHECK lands NOT VALID (the V103/V104 + V209 discipline verbatim):
--     the validation scan runs in its own migration (V221) under SHARE
--     UPDATE EXCLUSIVE alone — never inside this transaction, where the
--     ADD's ACCESS EXCLUSIVE lock would block the live payment surfaces.
--     Every existing row satisfies it by construction (the column arrives
--     NULL), so the validate leg is a formality the discipline still
--     requires.
--
-- Numbering note: V215–V218 are claimed by the in-flight delivery
-- vertical slice (PR #604) and V219 by the variant model (PR #613); this
-- slice takes V220/V221 so the three open tracks never collide in
-- flyway_schema_history regardless of merge order.
--
-- The Envers mirror (the V24 convention; the V33 lesson: a base table
-- without its _aud twin breaks audit INSERTs silently): the column joins
-- payment_intents_aud as nullable — an ADD revision row written by the
-- legacy runtime carries NULL honestly.

ALTER TABLE payment_intents
    ADD COLUMN commission_rate NUMERIC(5,4);

ALTER TABLE payment_intents_aud
    ADD COLUMN commission_rate NUMERIC(5,4);

ALTER TABLE payment_intents
    ADD CONSTRAINT chk_payment_intents_commission_rate
        CHECK (commission_rate IS NULL
               OR (commission_rate >= 0 AND commission_rate <= 1)) NOT VALID;

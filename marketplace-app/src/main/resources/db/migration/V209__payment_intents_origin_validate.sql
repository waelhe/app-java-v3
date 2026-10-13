-- V209 — The payment-intent origin pair's validate leg (the discipline's
-- own closure). V184 (the ORDER origin) and V200 (the LOAN origin) each
-- rewrote chk_payment_intents_origin and ck_payment_intents_origin_pairing
-- in place — DROP, then re-ADD NOT VALID, the V103 discipline's letter:
-- inside one transaction the ADD's ACCESS EXCLUSIVE lock would still be
-- held during the validation scan, blocking the ordinary reads and writes
-- of the payment surfaces (the V166/V167, V74/V75, V203/V204 and
-- V206/V207 precedent verbatim — every NOT VALID on this tree owns its
-- separate validate migration). This is that owed leg: the scan runs under
-- the weaker SHARE UPDATE EXCLUSIVE lock, every existing row satisfies the
-- widened shapes by construction (ORDER/LOAN rows are new; legacy rows
-- carry their booking/ad pairing columns), and the constraints leave the
-- "NOT VALID" state behind — the catalog's honest, validated end state,
-- the applied-state guarantee main's V104 gave, restored.
ALTER TABLE payment_intents VALIDATE CONSTRAINT chk_payment_intents_origin;
ALTER TABLE payment_intents VALIDATE CONSTRAINT ck_payment_intents_origin_pairing;

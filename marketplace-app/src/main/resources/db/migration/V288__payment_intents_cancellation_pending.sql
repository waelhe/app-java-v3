-- CodeRabbit agent findings (2026-10-26T11:43 export), row 15 — the
-- cancellation-pending state: an in-flight payment abandoned by a domain
-- cancellation (booking/order/delivery/loan auto-refund edge) is NOT
-- terminally failed — the remote capture may still win the race. The local
-- record now says exactly that: CANCELLATION_PENDING (the abandonment
-- decision is committed; the remote race is unresolved). The provider's
-- own truth resolves it later: payment_intent.succeeded settles the books
-- and pays the abandonment's ONE durable idempotent refund back;
-- payment_intent.payment_failed / canceled / expired resolve it to FAILED
-- (no money moved). The V27 status vocabulary extends by exactly this one
-- word; the payments table keeps its own five-word vocabulary untouched
-- (its row stays PENDING while the race is open — the remote outcome owns
-- its terminal state).

ALTER TABLE payment_intents DROP CONSTRAINT payment_intents_status_check;

ALTER TABLE payment_intents ADD CONSTRAINT payment_intents_status_check
    check (status in ('CREATED','PROCESSING','SUCCEEDED','FAILED','CANCELLED',
                      'REFUNDED','PARTIALLY_REFUNDED','CANCELLATION_PENDING'));

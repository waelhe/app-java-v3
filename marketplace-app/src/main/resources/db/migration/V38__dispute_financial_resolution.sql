-- L24 (feature-expansion roadmap §5, Week 2): dispute resolution with a
-- financial decision. The resolve decision carries an outcome
-- (REFUND_CONSUMER / RELEASE_PROVIDER / NO_ACTION); on REFUND_CONSUMER the
-- resolve invokes the existing refund path and records the movement ON the
-- dispute — the refund reference columns link the dispute to the refunded
-- payment (the dispute row IS the dispute_id <-> refund linkage).
-- The Envers mirror (disputes_aud, V24) gains the same columns so the
-- @Audited snapshot keeps writing — every decision leaves an audit trace.

ALTER TABLE disputes
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(20),
    ADD COLUMN IF NOT EXISTS refund_payment_id UUID,
    ADD COLUMN IF NOT EXISTS refunded_amount_cents BIGINT;

ALTER TABLE disputes_aud
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(20),
    ADD COLUMN IF NOT EXISTS refund_payment_id UUID,
    ADD COLUMN IF NOT EXISTS refunded_amount_cents BIGINT;

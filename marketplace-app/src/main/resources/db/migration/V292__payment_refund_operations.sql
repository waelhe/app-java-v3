-- Review round 1 (the payments trio — the dispute refund's frozen
-- operation): the durable journal for dispute-scoped remote refunds.
--
-- The measured windows the journal closes (all three ride the SAME root:
-- the resolve transaction can roll back AFTER the remote refund executed,
-- and Stripe's idempotency layer is the ONLY record of the operation —
-- a record that is invisible to us and expires after 24 hours):
--   * the amount freeze — a re-approval that passes a DIFFERENT amount
--     would re-ride the same amount-less dispute key with different
--     params, which Stripe rejects outright (the idempotency-key
--     contract: "Keys for idempotent requests can only be used with the
--     same parameters they were first used with") — the retry could
--     never complete. The journal freezes the first submission's amount
--     in a transaction of its own (REQUIRES_NEW — it survives the
--     rollback window by construction) and every later submission is
--     checked against it;
--   * the executed-match — after the rollback, the charge.refunded
--     webhook syncs the local books to the remote cumulative actual,
--     but the retry's cap check (alreadyRefunded + amount > charged)
--     rejects the VERY refund that already executed, leaving the dispute
--     open forever behind money that moved. The journal's
--     books-at-submission snapshot makes the executed test derivable:
--     books_now >= books_at_submit + frozen_amount means the operation's
--     refund is already in the remote actual — the retry returns the
--     reconciled state instead of re-applying a cap or re-submitting;
--   * the key-expiry double-refund — Stripe deletes idempotency records
--     after 24 hours, so a late retry under the same key would create a
--     SECOND remote refund within the cap's allowance. The journal is
--     the local identity that does not expire: the executed-match above
--     simply never re-submits an executed operation.
--
-- Deliberately NOT Envers-audited and NOT a BaseEntity: this is an
-- operational journal of provider-side facts, not domain state — its
-- rows are born in their own REQUIRES_NEW transactions and updated as
-- the provider answers; a _aud twin would add nothing (the V33 lesson
-- applies to @Audited entities only).
--
-- Checksum registered in migration-checksums.properties in this same
-- commit (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE payment_refund_operations (
    idempotency_key       varchar(200) PRIMARY KEY,
    payment_id            uuid         NOT NULL,
    amount_cents          bigint,
    books_at_submit_cents bigint       NOT NULL,
    remote_refund_id      varchar(100),
    status                varchar(20)  NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now()
);

-- The dispute purge/reconciliation seam reads by payment; the journal's
-- own lifecycle reads go through the primary key.
CREATE INDEX idx_payment_refund_operations_payment
    ON payment_refund_operations (payment_id);

-- B-04 (compliance plan 0.4 — the message-send idempotency surface):
-- The caller's replay key, mirroring payment_intents' V5 contract exactly
-- (varchar(64) UNIQUE + a null-scoped lookup index), applied with the
-- V56/V97 audited-table discipline: the live table and its _aud mirror
-- together, nullable in the mirror (a DEL revision carries the id alone).
--
-- Race backstop: the UNIQUE constraint makes the concurrent same-key
-- double-submit lose at flush — never a duplicate row. Sequential retries
-- are answered by the service's replay lookup (findByIdempotencyKey);
-- @Version (BaseEntity, already on every message row) guards row updates
-- per the Data JPA optimistic-locking contract.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

ALTER TABLE messages_aud ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

ALTER TABLE messages
    ADD CONSTRAINT uq_messages_idempotency_key UNIQUE (idempotency_key);

CREATE INDEX IF NOT EXISTS idx_messages_idempotency
    ON messages (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

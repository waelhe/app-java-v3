-- B-04 (compliance plan 0.4 — the message-send idempotency surface):
-- The caller's replay key, one key space PER SENDER (the CodeRabbit
-- round-1 root adoption: a client-chosen key such as
-- "msg-2026-10-07-001" is realistic to collide ACROSS senders — the
-- global UNIQUE of payment_intents' V5 would turn the second sender's
-- legitimate message into an error that also leaks that another user
-- burned the key; the per-sender scoping keeps every sender's key space
-- independent), applied with the V56/V97 audited-table discipline: the
-- live table and its _aud mirror together, nullable in the mirror (a
-- DEL revision carries the id alone).
--
-- The constraint's own index on (sender_id, idempotency_key) serves the
-- replay lookup — no separate lookup index (the earlier partial index
-- duplicated the constraint's index: every insert paid for both).
--
-- Race backstop: the UNIQUE constraint makes the concurrent same-key
-- double-submit lose at flush — never a duplicate row — and the
-- service's catch-and-replay answers the loser with the winner's row
-- (the 200 replay the API promises). Sequential retries are answered
-- by the service's replay lookup
-- (findBySenderIdAndIdempotencyKey); @Version (BaseEntity, already on
-- every message row) guards row updates per the Data JPA
-- optimistic-locking contract.
ALTER TABLE messages ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

ALTER TABLE messages_aud ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(64);

ALTER TABLE messages
    ADD CONSTRAINT uq_messages_sender_idempotency_key UNIQUE (sender_id, idempotency_key);

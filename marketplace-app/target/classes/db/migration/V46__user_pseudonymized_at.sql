-- I7 Phase 1 (account-pseudonymization-plan §5-هـ, adopted by PR #276):
-- account pseudonymization & data-subject rights. ONE nullable status
-- column on users:
--   pseudonymized_at TIMESTAMPTZ
--     NULL     = a live account (every existing row — backfill-free,
--                byte-compatible for every existing query path);
--     NOT NULL = the account's direct identifiers were replaced by the
--                plan §5-أ one-transaction operation (subject -> the
--                derived replacement, email/display_name -> NULL) and the
--                login identity rows were deleted. The column doubles as:
--       - the idempotence marker (a second pseudonymize call is a
--         documented no-op — §5-أ step 2), and
--       - the "former member" switch for read DTOs (the neutral label is
--         rendered at the response level, never stored — §5-أ step 3).
-- No enum: the existing role/enabled pair governs the other surfaces
-- (L23 disable is a separate, reversible channel). No index: no query
-- filters on it today — the guard runs per-account through the users
-- primary key; an index is added when a real filter exists with its
-- measured justification.
-- The Envers mirror gains the same column (V24 convention — the V44/V45
-- pattern for column additions) so @Audited snapshots keep writing: the
-- V33 lesson, base-table columns without the _aud twin break audit
-- INSERTs silently under mocked tests (the users_aud INSERT issued by
-- Envers carries every mapped column of the entity).

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS pseudonymized_at TIMESTAMPTZ;

ALTER TABLE users_aud
    ADD COLUMN IF NOT EXISTS pseudonymized_at TIMESTAMPTZ;

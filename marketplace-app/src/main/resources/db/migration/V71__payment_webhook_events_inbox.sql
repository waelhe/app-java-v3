-- R10 (comprehensive-review-ar fix plan §4/R10 — Wave 2, PR body carries the
-- wave story): the durable webhook inbox columns on payment_webhook_events.
--
-- The measured defect (comprehensive-review-ar-1.md, verified in code at
-- 73da7395): WebhookEventRecorder.record() commits the dedup row
-- (REQUIRES_NEW) BEFORE the dispatch runs, and the compensating delete only
-- covers a live dispatch failure (catch RuntimeException). A worker stop
-- between the two steps (deploy, OOM, node loss) left the row committed
-- forever while the settlement never happened — the provider's retries met
-- the dedup gate and were answered already-processed against a tombstone:
-- the money transition was lost permanently.
--
-- The fix's storage half is this migration: the row grows from a bare dedup
-- tombstone into the durable inbox — the complete re-delivery contract
-- (event type was already there; resolved intent, external id, refund
-- snapshot amount are new) plus the raw provider payload for inspection and
-- a processing_state lifecycle the recovery sweep (WebhookInboxRecovery,
-- @Scheduled — the Modulith Event Publication Registry pattern applied to
-- provider notifications: completed rows archived, incomplete re-delivered).
--
-- Column shapes follow the standing precedents:
--   * payload JSONB — the V48/amenities and V54/saved-searches official
--     Hibernate JSON mapping (@JdbcTypeCode(SqlTypes.JSON)); nullable: the
--     legacy HMAC route carries no body at all (plan §4/R10: "jsonb
--     nullable").
--   * processing_state — the RECEIVED/SETTLED/FAILED lifecycle, CHECK'd in
--     the V44 shape (V70's same-file NOT VALID + VALIDATE precedent — this
--     table is low-traffic: one row per provider notification, no hot-path
--     lock concern the V56/V57 split discipline exists to protect).
--   * payment_intent_id / external_id — plain UUID/VARCHAR columns, no FK
--     across module borders (the V32/media_assets discipline: the webhook
--     row references, it never joins).
--   * refund_amount_cents — the charge.refunded dispatch input stored at
--     record time so the recovery sweep replays the event WITHOUT the
--     channel being bound (the raw payload stays the human-readable source).
--   * failure_reason — the inspectable why of a terminally FAILED row (plan
--     §4/R10 point 5: "FAILED الصريح يحمل سببًا قابلًا للفحص").
--
-- Backfill honesty: every pre-V71 row is the tombstone of an event whose
-- dispatch COMPLETED (the compensating delete removed the failed-dispatch
-- rows; the crashed-dispatch rows are the exactly-this-fix's blind spot and
-- cannot be distinguished retroactively — the honest default for a completed
-- dispatch is SETTLED). The DEFAULT is then DROPPED: the entity's factory is
-- the only writer and it always states RECEIVED explicitly — a silent
-- default would mask a future writer forgetting the lifecycle.
--
-- Numbering note (plan §5): V71 is taken on this branch from main's latest
-- (V70) per the SYSTEM.md §7 rule; the open #461/#462 branches also carry a
-- V71 — whichever merges first keeps the number, the other renumbers before
-- its own merge.
--
-- Checksum registered in migration-checksums.properties in this same PR
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE payment_webhook_events
    ADD COLUMN IF NOT EXISTS processing_state   VARCHAR(20) NOT NULL DEFAULT 'SETTLED',
    ADD COLUMN IF NOT EXISTS payload            JSONB,
    ADD COLUMN IF NOT EXISTS payment_intent_id  UUID,
    ADD COLUMN IF NOT EXISTS external_id        VARCHAR(200),
    ADD COLUMN IF NOT EXISTS refund_amount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS failure_reason     VARCHAR(1000);

ALTER TABLE payment_webhook_events
    ALTER COLUMN processing_state DROP DEFAULT;

ALTER TABLE payment_webhook_events
    ADD CONSTRAINT chk_payment_webhook_events_processing_state
        CHECK (processing_state IN ('RECEIVED', 'SETTLED', 'FAILED')) NOT VALID;

ALTER TABLE payment_webhook_events
    VALIDATE CONSTRAINT chk_payment_webhook_events_processing_state;

-- The recovery sweep's read: RECEIVED rows are the rare exception (the inbox
-- is empty in steady state), so a partial index on the staleness dimension
-- keeps every sweep an indexed near-empty lookup (V67 partial-index shape).
CREATE INDEX IF NOT EXISTS idx_payment_webhook_events_recovery
    ON payment_webhook_events (created_at)
    WHERE processing_state = 'RECEIVED' AND is_deleted = FALSE;

-- Envers mirror (V24 convention — the V33 lesson: base-table columns without
-- the _aud twin break audit INSERTs silently; nullable there: a DEL revision
-- row carries the id alone, the V24/V49 precedent).
ALTER TABLE payment_webhook_events_aud
    ADD COLUMN IF NOT EXISTS processing_state    VARCHAR(20),
    ADD COLUMN IF NOT EXISTS payload             JSONB,
    ADD COLUMN IF NOT EXISTS payment_intent_id   UUID,
    ADD COLUMN IF NOT EXISTS external_id         VARCHAR(200),
    ADD COLUMN IF NOT EXISTS refund_amount_cents BIGINT,
    ADD COLUMN IF NOT EXISTS failure_reason      VARCHAR(1000);

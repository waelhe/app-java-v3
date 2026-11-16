-- I8 (internal free plan §6, roadmap §7 — the deferred product decision
-- "تقييم المزوّد للمستهلك (اتجاه معاكس)" now executed on the user's order):
-- the two-way review. The SAME reviews entity gains a direction and a
-- reverse-reviewee:
--   direction VARCHAR(20) NOT NULL DEFAULT 'CONSUMER_TO_PROVIDER'
--     - the pre-existing direction every row carries (all current reviews
--       are consumer -> provider); the default keeps the migration
--       backfill-free and byte-compatible for every existing query path.
--     - 'PROVIDER_TO_CONSUMER' = the provider rates the booking's consumer
--       (I8's reverse direction).
--   reviewee_id UUID NULL
--     - filled ONLY on PROVIDER_TO_CONSUMER rows: the reviewed consumer's
--       user id. NULL on forward rows because the reviewed provider already
--       lives in provider_id (profiles.id space) — no denormalization, no
--       mixed id spaces in one column.
-- Envers audit mirror gains both columns (V24 convention, the V43/V44
-- pattern) so @Audited snapshots keep writing (the V33 lesson).
-- The provider rating average (L21) MUST count forward reviews only —
-- the aggregate query gains the direction filter in the same PR; a
-- provider-authored rating of the consumer never pollutes the provider's
-- own average.

ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS direction VARCHAR(20) NOT NULL DEFAULT 'CONSUMER_TO_PROVIDER';

ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS reviewee_id UUID;

ALTER TABLE reviews
    ADD CONSTRAINT chk_reviews_direction_kind
    CHECK (direction IN ('CONSUMER_TO_PROVIDER', 'PROVIDER_TO_CONSUMER'));

-- CodeRabbit #270 Major (adopted from the root): V6's uq_review_booking_active
-- enforced ONE review per booking in total — incompatible with the two-way
-- review (a booking carries one forward AND one reverse entry). Swap it for
-- the per-direction partial unique index: one ACTIVE review per
-- (booking, direction), concurrent-insert-proof at the database level — the
-- same defense depth as the application-level existsByBookingIdAndDirection.
-- Compatibility: every pre-V45 row carries direction 'CONSUMER_TO_PROVIDER'
-- (the DEFAULT above), so (booking_id, direction) uniqueness holds exactly
-- where booking_id uniqueness held — a lossless, atomic index swap inside
-- this transaction.
DROP INDEX IF EXISTS uq_review_booking_active;
CREATE UNIQUE INDEX IF NOT EXISTS uq_reviews_booking_direction_active
    ON reviews (booking_id, direction) WHERE is_deleted = false;

ALTER TABLE reviews_aud
    ADD COLUMN IF NOT EXISTS direction VARCHAR(20) DEFAULT 'CONSUMER_TO_PROVIDER';

ALTER TABLE reviews_aud
    ADD COLUMN IF NOT EXISTS reviewee_id UUID;

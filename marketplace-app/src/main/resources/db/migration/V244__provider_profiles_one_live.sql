-- ONE LIVE PROVIDER PROFILE PER ACCOUNT (the V233/V234 "one live" family):
-- provider_profiles.user_id has carried only a plain index since the
-- user_id column landed — nothing stopped a second live row for the same
-- account, and ProviderRepository.findByUserId returns Optional, so a
-- duplicate would surface as IncorrectResultSizeDataAccessException, not
-- as an honest 409. Measured on the production channel 2026-10-20: exactly
-- one duplicate pair existed (the shared-production E2E provisioning
-- script's interrupted first pass created a profile, threw after the
-- creation, and its re-run created a second one) — both rows PENDING,
-- identical content, born minutes apart.
--
-- This migration converges existing data onto the invariant and then
-- enforces it with the partial unique index:
--   1) every live profile except the EARLIEST per account (created_at, id
--      — the deterministic tiebreak) is soft-deleted in place;
--   2) uq_provider_profiles_one_live pins one live row per user_id
--      (is_deleted = FALSE), so the concurrent race between two
--      ProviderService.create calls answers the DB-level 409 while the
--      service's friendly pre-write check answers the ordinary one (the
--      V233 division of labor verbatim).
--
-- Envers note (deliberate, documented): the convergence UPDATE writes
-- is_deleted directly — provider_profiles_aud carries no row for it. The
-- audit trail's subject is business edits through the application; a
-- schema-invariant convergence at the invariant's birth is the one
-- documented exception (same stance as every NOT VALID/VALIDATE and
-- CONCURRENTLY house migration: DDL discipline, not a business edit).
--
-- Lock note: the table is small (single-digit rows on the production
-- channel) — a transactional build with the convergence UPDATE is
-- sub-millisecond and keeps the dedup + the index atomic together (a
-- split build would open a window where a new duplicate could land
-- between the two statements and break the index creation).
--
-- Overlap-window note (CodeRabbit round 1, adopted from the root): the
-- previous deployment can still serve ProviderService.create writes while
-- this migration runs at startup (the Railway blue/green overlap — the
-- V243 CONCURRENTLY context). SHARE mode blocks those writes for the
-- milliseconds the migration holds: an insert that lands after the lock
-- hits the finished unique index and answers the honest DB-level 409,
-- instead of failing the migration's index creation and costing a deploy
-- retry (the transactional rollback would self-heal, but the lock makes
-- the window deterministic).
--
-- Unlinked-profiles note (CodeRabbit round 1, adopted from the root):
-- user_id is nullable (V22 added it nullable; V23's backfill and V39's
-- repair leave unmatched rows NULL). Window PARTITION BY groups NULLs
-- into ONE partition, so unlinked live rows would be wrongly ranked as
-- duplicates of each other — the ranking below excludes them; the
-- partial unique index permits multiple NULLs by PostgreSQL's own
-- documented semantics, so the invariant only binds account-linked rows.

LOCK TABLE provider_profiles IN SHARE MODE;

UPDATE provider_profiles
SET is_deleted = TRUE
WHERE id IN (
    SELECT id
    FROM (
        SELECT id, row_number() OVER (
            PARTITION BY user_id
            ORDER BY created_at ASC, id ASC
        ) AS rn
        FROM provider_profiles
        WHERE is_deleted = FALSE AND user_id IS NOT NULL
    ) ranked
    WHERE ranked.rn > 1
);

CREATE UNIQUE INDEX uq_provider_profiles_one_live
    ON provider_profiles (user_id)
    WHERE is_deleted = FALSE;

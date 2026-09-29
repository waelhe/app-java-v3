-- R3 (comprehensive-review-ar-fix plan §4/R3 — duplicate slot prevention):
-- the partial unique index that makes one live slot per provider window a
-- DATABASE invariant, plus the one-time repair of the duplicates the old
-- generator produced. The defect: `generateSlotsForDate` checked existence
-- with a `booked = false` filter, so a BOOKED slot was invisible to the
-- check and the daily generation inserted a fresh OPEN copy of the same
-- window — an open duplicate riding next to the held row (V15 has no
-- unique constraint on the window, so nothing stopped it). The service
-- layer's existence check now ignores `booked` (a booked slot IS the
-- window); this index is the backstop for any path that still races past
-- an application-level check — the V67 house shape: application pre-check
-- for the friendly failure, DB constraint for the concurrent race (the
-- losing racer gets 23505 wrapped in Spring's DataIntegrityViolationException,
-- the generator's documented best-effort per-rule catch handles it).
--
-- PARTIAL on live rows only: `WHERE is_deleted = FALSE` — a soft-deleted
-- slot never blocks its window from being re-opened (the same
-- live-rows-only shape as uq_content_reports_reporter_target,
-- uq_conversations_direct_pair and every V7 partial index; the entity's
-- @SoftDelete filter means application reads never see dead rows either).
--
-- REPAIR (merge to the OLDEST live row — the plan's own wording):
-- (1) carry the hold: if any live sibling of the window is booked, the
--     survivor inherits booked = TRUE together with the sibling's
--     held_by_booking_id (V72's backfill already resolved it), so the
--     repair never silently frees a held window;
-- (2) soft-delete every live row that has an older live sibling —
--     exactly one live row (the oldest by created_at, id tiebreak) per
--     window remains. Soft delete, not hard delete: the house convention
--     keeps history queryable and Envers' last revision per row intact;
--     the partial index tolerates the dead rows by design.
-- Both UPDATEs are IDEMPOTENT (guards: is_deleted = false, booked = false,
-- existence of an older sibling) — safe on the rerun after repair, which
-- matters because this script runs NON-TRANSACTIONALLY (below).
--
-- NON-TRANSACTIONAL BUILD (the V51/V67 in-tree precedent verbatim):
-- (1) the sibling file V73__availability_slots_window_unique.sql.conf sets
-- `executeInTransaction = false` (Redgate Flyway docs, "Execute In
-- Transaction Setting": "Note that this setting can be set from Script
-- Configuration in addition to project configuration"); PostgreSQL cannot
-- run CREATE INDEX CONCURRENTLY inside a transaction block.
-- (2) the PROJECT setting spring.flyway.postgresql.transactional-lock:
-- false (application.yml, in place since V51) — without it Flyway's
-- default TRANSACTIONAL advisory lock predates the concurrent build's
-- snapshot waits and boot hangs indefinitely.
--
-- RETRY SEMANTICS (PostgreSQL, "Building Indexes Concurrently"): a failed
-- concurrent build is entered as an INVALID index in the system catalogs
-- BEFORE the table scans — the documented recovery is drop the index and
-- try again; a FAILED V73 leaves the migration failed (loud, Flyway
-- records it), never a silent skip.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- (1) carry the hold onto each window's surviving (oldest live) row.
UPDATE availability_slots survivor
SET booked = true,
    held_by_booking_id = (
        SELECT sibling.held_by_booking_id
        FROM availability_slots sibling
        WHERE sibling.is_deleted = false
          AND sibling.provider_id = survivor.provider_id
          AND sibling.starts_at = survivor.starts_at
          AND sibling.ends_at = survivor.ends_at
          AND sibling.booked = true
        ORDER BY sibling.created_at ASC, sibling.id ASC
        LIMIT 1
    )
WHERE survivor.is_deleted = false
  AND survivor.booked = false
  AND EXISTS (
        SELECT 1
        FROM availability_slots sibling
        WHERE sibling.is_deleted = false
          AND sibling.provider_id = survivor.provider_id
          AND sibling.starts_at = survivor.starts_at
          AND sibling.ends_at = survivor.ends_at
          AND sibling.booked = true
  )
  AND survivor.id = (
        SELECT oldest.id
        FROM availability_slots oldest
        WHERE oldest.is_deleted = false
          AND oldest.provider_id = survivor.provider_id
          AND oldest.starts_at = survivor.starts_at
          AND oldest.ends_at = survivor.ends_at
        ORDER BY oldest.created_at ASC, oldest.id ASC
        LIMIT 1
  );

-- (2) soft-delete every live row that has an older live sibling — the
-- tuple comparison (created_at, id) is PostgreSQL's row-value ordering,
-- a total order that makes "oldest" well-defined even under timestamp ties.
UPDATE availability_slots younger
SET is_deleted = true
WHERE younger.is_deleted = false
  AND EXISTS (
        SELECT 1
        FROM availability_slots older
        WHERE older.is_deleted = false
          AND older.provider_id = younger.provider_id
          AND older.starts_at = younger.starts_at
          AND older.ends_at = younger.ends_at
          AND (older.created_at, older.id) < (younger.created_at, younger.id)
  );

CREATE UNIQUE INDEX CONCURRENTLY uq_availability_slots_live_window
    ON availability_slots (provider_id, starts_at, ends_at)
    WHERE is_deleted = false;

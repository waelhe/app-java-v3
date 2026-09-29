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
-- the generator's documented best-effort per-rule catch absorbs it — one
-- rule, one REQUIRES_NEW transaction).
--
-- PARTIAL on live rows only: `WHERE is_deleted = FALSE` — a soft-deleted
-- slot never blocks its window from being re-opened (the same
-- live-rows-only shape as uq_content_reports_reporter_target,
-- uq_conversations_direct_pair and every V7 partial index; the entity's
-- @SoftDelete filter means application reads never see dead rows either).
--
-- REPAIR, in two converging steps (merge to the OLDEST live row — the
-- plan's own wording; then reconcile the hold from the ACTIVE bookings):
--
-- (1) soft-delete every live row that has an older live sibling — exactly
--     one live row (the oldest by created_at, id tiebreak) per window
--     remains. Soft delete, not hard delete: the house convention keeps
--     history queryable and Envers' last revision per row intact; the
--     partial index tolerates the dead rows by design. The tuple
--     comparison (created_at, id) is PostgreSQL's row-value ordering — a
--     total order that makes "oldest" well-defined even under timestamp
--     ties.
--
-- (2) RECONCILE THE HOLD FROM THE ACTIVE BOOKINGS (CodeRabbit round 1 on
--     this PR, adopted from the root — verified against the code before
--     action: with pre-fix duplicates, TWO booked rows of one window could
--     carry DIFFERENT owners — each duplicate was booked by its own
--     confirming booking — and V72's row-level backfill assigns the same
--     window-derived booking to every row, which cannot decide between
--     two divergent claims; deleting the younger booked row then strands
--     its booking's cancellation (no row left to release) while the
--     retained owner's cancel could reopen the window although another
--     booking is still active). The reconciliation therefore derives the
--     post-dedup state from the BOOKINGS side — the live claims — not
--     from the slot rows' backfilled values:
--       (a) a live window with an ACTIVE booking (CONFIRMED or COMPLETED
--           on the exact window — the statuses that hold, per the code:
--           confirm()/autoConfirm() book, cancel()/autoCancel() release)
--           is booked, owned by the LATEST-updated active booking — the
--           deterministic total order (updated_at, id); with bookSlot
--           last-writer-wins on the slot row, the latest confirmed claim
--           is the one that actually holds the window. Every other live
--           claim's cancel is a SAFE no-op (the window never reopens
--           while the holder stays active).
--       (b) a live BOOKED window with NO active booking is pre-fix
--           residue (the R2 defect released the wrong duplicate row and
--           left the mate booked) — it reopens: booked = false, no owner.
--           Without this step those windows would stay booked forever
--           with no booking able to release them.
--     Both statements are IDEMPOTENT (guards: is_deleted = false, booked
--     = true, existence) — safe on the rerun after repair, which matters
--     because this script runs NON-TRANSACTIONALLY (below).
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
-- DEPLOY-OVERLAP WINDOW (CodeRabbit round 4 on this PR, verified against
-- the deployment facts before action): Railway keeps the PREVIOUS
-- deployment serving while the new one boots — and Flyway runs during
-- that boot, so the OLD deployment's generator (still carrying the
-- booked-blind probe this wave fixes) can in principle commit a
-- duplicate between this script's repair statements and the concurrent
-- build's scans, leaving an INVALID index and a failed migration. The
-- governing plan MANDATES this exact tradeoff: the V67-verbatim
-- non-blocking pattern WITH this documented recovery — PostgreSQL
-- cannot hold a lock across a CONCURRENTLY build, and a locking plain
-- build (the alternative) is the pattern the repository's V51/V67
-- lesson deliberately rejects. The exposure is bounded and measured:
-- the racing insert needs the daily generation tick to land inside the
-- deploy's migration seconds AND a booked window with no open duplicate
-- (the old probe's only miss case); the terminal state is a LOUD failed
-- deploy with NO downtime (Railway retains the previous deployment —
-- the new one never became healthy) and NO corruption; and the repair
-- is idempotent, so the retried deploy re-cleans whatever committed in
-- the window and rebuilds. Recovery runbook, should it ever fire: drop
-- the INVALID uq_availability_slots_live_window, re-run the deployment
-- — this script's three repair statements are safe to re-execute.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- (1) soft-delete every live row that has an older live sibling — the
-- oldest live row per window survives.
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

-- (2a) reconcile: a live window with an ACTIVE booking is booked and
-- owned by that booking — the latest active claim on the window.
UPDATE availability_slots s
SET booked = true,
    held_by_booking_id = (
        SELECT b.id
        FROM bookings b
        WHERE b.provider_id = s.provider_id
          AND b.starts_at = s.starts_at
          AND b.ends_at = s.ends_at
          AND b.status IN ('CONFIRMED', 'COMPLETED')
          AND b.is_deleted = false
        ORDER BY b.updated_at DESC, b.id DESC
        LIMIT 1
    )
WHERE s.is_deleted = false
  AND EXISTS (
        SELECT 1
        FROM bookings b
        WHERE b.provider_id = s.provider_id
          AND b.starts_at = s.starts_at
          AND b.ends_at = s.ends_at
          AND b.status IN ('CONFIRMED', 'COMPLETED')
          AND b.is_deleted = false
  );

-- (2b) reconcile: a live BOOKED window with no active booking is pre-fix
-- residue — it reopens instead of staying stuck booked forever.
UPDATE availability_slots s
SET booked = false,
    held_by_booking_id = NULL
WHERE s.is_deleted = false
  AND s.booked = true
  AND NOT EXISTS (
        SELECT 1
        FROM bookings b
        WHERE b.provider_id = s.provider_id
          AND b.starts_at = s.starts_at
          AND b.ends_at = s.ends_at
          AND b.status IN ('CONFIRMED', 'COMPLETED')
          AND b.is_deleted = false
  );

CREATE UNIQUE INDEX CONCURRENTLY uq_availability_slots_live_window
    ON availability_slots (provider_id, starts_at, ends_at)
    WHERE is_deleted = false;

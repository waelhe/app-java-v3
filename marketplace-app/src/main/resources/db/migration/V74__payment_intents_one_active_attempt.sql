-- R4 (comprehensive-review-ar-fix plan §4/R4 — one collectible attempt per
-- booking): the partial unique index that makes ONE live collectible
-- payment intent per booking a DATABASE invariant, plus the one-time repair
-- of the duplicates the optional idempotency key never prevented. The
-- defect: `createIntent`'s only dedup surface was the CALLER-SUPPLIED
-- idempotency key (optional — a second intent for the same booking slipped
-- through whenever the key was absent or fresh), so multiple intents for
-- one booking were reachable by construction; processing each of them
-- charges the booking twice. The decided model (the review's own product
-- decision): one COLLECTIBLE attempt at a time — a new intent requires the
-- previous attempt to have FAILED or been CANCELLED; the service-layer
-- guard reads the live rows' state and answers a friendly Conflict, this
-- index is the backstop for the concurrent race (the V67 house shape:
-- application pre-check for the friendly failure, DB constraint for the
-- race — the losing racer gets 23505 wrapped in Spring's
-- DataIntegrityViolationException).
--
-- PARTIAL on collectible live rows only: `WHERE is_deleted = false AND
-- status IN ('CREATED','PROCESSING')` — the two states an intent can still
-- collect money in. Terminal and collected rows (FAILED / CANCELLED /
-- SUCCEEDED / PARTIALLY_REFUNDED / REFUNDED) coexist with the live attempt
-- by design: they are the booking's payment HISTORY, and a soft-deleted
-- row never blocks its booking from a fresh attempt (the same live-rows
-- partial shape as uq_availability_slots_live_window / uq_content_reports;
-- the entity's @SoftDelete filter means application reads never see dead
-- rows either).
--
-- REPAIR, in two converging steps (every duplicate the defect produced is
-- either a post-payment double-charge window or a stale retry):
--
-- (1) a booking that already COLLECTED money (a live row in SUCCEEDED /
--     PARTIALLY_REFUNDED / REFUNDED — the states where money moved) has
--     every live collectible row repaired: those rows are the defect's
--     double-charge window itself (the booking is paid; a collectible
--     sibling could still be processed and charged again). The repair
--     uses the state machine's OWN outcome per state (CodeRabbit round 1,
--     verified against PaymentIntentStatus.TRANSITIONS before action):
--     CREATED -> CANCELLED (cancelUnpaid's mapping — the abandoned
--     attempt, history visible, Envers runtime trail intact) and
--     PROCESSING -> FAILED (failInFlight's mapping — TRANSITIONS admits
--     no PROCESSING -> CANCELLED) with the intent's in-flight payments
--     row marked FAILED in the SAME statement (failInFlight marks both;
--     the data-modifying CTE leaves no window between them). The
--     migration's convergence is a one-time data repair documented here
--     (the V73 precedent: reconciliation SQL does not write Envers
--     revisions).
--
-- (2) a booking that collected NOTHING keeps its LATEST collectible
--     attempt (the consumer's most recent initiation — the one they
--     would process; the total order (created_at, id) alone decides,
--     no preference between CREATED and PROCESSING rows) and repairs the
--     OLDER siblings with the same state-machine outcomes and payment
--     sync. The tuple comparison (created_at, id) is PostgreSQL's
--     row-value ordering — a total order that makes "latest"
--     well-defined even under timestamp ties, the same deterministic
--     order the scoped repository searches
--     (findFirstByBookingIdAndStatusIn...OrderByCreatedAtDescIdDesc)
--     read with.
--
--     Both statements are IDEMPOTENT (guards: is_deleted = false, status
--     IN the collectible set) — safe on the rerun after repair, which
--     matters because this script runs NON-TRANSACTIONALLY (below).
--
-- NON-TRANSACTIONAL BUILD (the V51/V67/V73 in-tree precedent verbatim):
-- (1) the sibling file V74__payment_intents_one_active_attempt.sql.conf
-- sets `executeInTransaction = false` (Redgate Flyway docs, "Execute In
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
-- try again; a FAILED V74 leaves the migration failed (loud, Flyway
-- records it), never a silent skip.
--
-- DEPLOY-OVERLAP WINDOW (the V73 round-4 lesson, applied by design):
-- Railway keeps the PREVIOUS deployment serving while the new one boots —
-- and Flyway runs during that boot, so the OLD deployment's createIntent
-- (no state guard) can in principle commit a fresh collectible intent
-- between this script's repair statements and the concurrent build's
-- scans, leaving an INVALID index and a failed migration. The governing
-- plan MANDATES the V67-verbatim non-blocking pattern WITH this
-- documented recovery — PostgreSQL cannot hold a lock across a
-- CONCURRENTLY build, and a locking plain build is the pattern the
-- repository's V51/V67 lesson deliberately rejects. The exposure is
-- bounded: the racing insert needs a consumer's create-intent call to
-- land inside the deploy's migration seconds; the terminal state is a
-- LOUD failed deploy with NO downtime (Railway retains the previous
-- deployment) and NO corruption; and the repair is idempotent, so the
-- retried deploy re-cleans whatever committed in the window and rebuilds.
-- Recovery runbook, should it ever fire: drop the INVALID
-- uq_payment_intents_one_active_attempt, re-run the deployment — this
-- script's two repair statements are safe to re-execute.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- (1) a booking that already collected money: every live collectible row
-- is a post-payment duplicate (the double-charge window) — repaired with
-- the state machine's OWN outcomes (CodeRabbit round 1 on this PR,
-- verified against the code before action): CREATED -> CANCELLED
-- (cancelUnpaid's own mapping) and PROCESSING -> FAILED (failInFlight's
-- own mapping — PaymentIntentStatus.TRANSITIONS admits no
-- PROCESSING -> CANCELLED). The FAILED intents' in-flight payments row
-- follows in the SAME statement (a data-modifying CTE — no window between
-- the intent and its payment): failInFlight marks both.
WITH repaired AS (
    UPDATE payment_intents dup
    SET status = CASE WHEN dup.status = 'PROCESSING' THEN 'FAILED' ELSE 'CANCELLED' END,
        updated_at = now()
    WHERE dup.is_deleted = false
      AND dup.status IN ('CREATED', 'PROCESSING')
      AND EXISTS (
            SELECT 1
            FROM payment_intents money
            WHERE money.booking_id = dup.booking_id
              AND money.is_deleted = false
              AND money.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
      )
    RETURNING dup.id, dup.status
)
UPDATE payments p
SET status = 'FAILED',
    updated_at = now()
FROM repaired r
WHERE p.payment_intent_id = r.id
  AND p.is_deleted = false
  AND p.status = 'PENDING'
  AND r.status = 'FAILED';

-- (2) a booking that collected nothing: keep the LATEST collectible
-- attempt (the total order (created_at, id) — no preference between
-- CREATED and PROCESSING, the repository contract is the LATEST row),
-- repair the older stale retries with the same state-machine outcomes
-- and the same payment sync.
WITH repaired AS (
    UPDATE payment_intents older
    SET status = CASE WHEN older.status = 'PROCESSING' THEN 'FAILED' ELSE 'CANCELLED' END,
        updated_at = now()
    WHERE older.is_deleted = false
      AND older.status IN ('CREATED', 'PROCESSING')
      AND EXISTS (
            SELECT 1
            FROM payment_intents newer
            WHERE newer.booking_id = older.booking_id
              AND newer.is_deleted = false
              AND newer.status IN ('CREATED', 'PROCESSING')
              AND (newer.created_at, newer.id) > (older.created_at, older.id)
      )
    RETURNING older.id, older.status
)
UPDATE payments p
SET status = 'FAILED',
    updated_at = now()
FROM repaired r
WHERE p.payment_intent_id = r.id
  AND p.is_deleted = false
  AND p.status = 'PENDING'
  AND r.status = 'FAILED';

CREATE UNIQUE INDEX CONCURRENTLY uq_payment_intents_one_active_attempt
    ON payment_intents (booking_id)
    WHERE is_deleted = false AND status IN ('CREATED', 'PROCESSING');

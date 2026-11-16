-- R2 (comprehensive-review-ar-fix plan §4/R2 — slot ownership): the
-- availability slot learns WHO holds it. Today `releaseSlot` frees the
-- first booked row matching the window regardless of which booking
-- booked it — cancelling a PENDING sibling releases the slot a
-- CONFIRMED booking owns (the review's measured finding: double-booking
-- through the back door). This migration adds the ownership column the
-- service layer claims at bookSlot time (`markBooked(bookingId)`), so a
-- release only ever lands on the slot the cancelling booking itself
-- booked. The column is nullable by design: ownership accrues from the
-- first post-migration confirm onward.
--
-- The Envers mirror gains the column in the V56/V37 style (the aud
-- table is ALTERed alongside its audited table; nullable there — a DEL
-- revision row carries the id alone, the V24/V54 precedent).
--
-- Pure schema migration — NO data conversion lives here (the plan's V7x-1
-- spec: column + mirror). All one-time data convergence — duplicate
-- repair AND the booking-derived hold reconciliation that supersedes any
-- row-level backfill (CodeRabbit round 1 on this PR: with pre-fix
-- duplicates, two booked rows of one window can carry divergent owners,
-- so the authoritative state must be derived from the ACTIVE bookings,
-- not seeded row by row) — is owned by V73, the wave's data-repair
-- migration, in the same deployment.
--
-- No CHECK, no index here: the uniqueness backstop is V73's partial
-- unique index (built CONCURRENTLY in its own non-transactional
-- script); this migration is a plain transactional ALTER.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE availability_slots
    ADD COLUMN IF NOT EXISTS held_by_booking_id uuid;

ALTER TABLE availability_slots_aud
    ADD COLUMN IF NOT EXISTS held_by_booking_id uuid;

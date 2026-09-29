-- R2 (comprehensive-review-ar-fix plan §4/R2 — slot ownership): the
-- availability slot learns WHO holds it. Today `releaseSlot` frees the
-- first booked row matching the window regardless of which booking
-- booked it — cancelling a PENDING sibling releases the slot a
-- CONFIRMED booking owns (the review's measured finding: double-booking
-- through the back door). This migration adds the ownership column the
-- service layer claims at bookSlot time (`markBooked(bookingId)`), so a
-- release only ever lands on the slot the cancelling booking itself
-- booked. The column is nullable by design: ownership accrues from the
-- first post-migration confirm onward, and the backfill below derives
-- the current holders from code facts.
--
-- The Envers mirror gains the column in the V56/V37 style (the aud
-- table is ALTERed alongside its audited table; nullable there — a DEL
-- revision row carries the id alone, the V24/V54 precedent).
--
-- BACKFILL (deterministic, derived from code facts — not an assumption):
-- bookSlot is called with the booking's EXACT window at
-- confirm()/autoConfirm() time (BookingService), and only
-- cancel()/autoCancel() release. Therefore the current holder of a
-- booked live slot is a live booking on the same (provider_id,
-- starts_at, ends_at) window whose status still owns the hold:
-- CONFIRMED or COMPLETED (PENDING never called bookSlot; CANCELLED
-- already released). When more than one exists (the R2 defect chain
-- itself: A confirmed, a sibling's cancel freed the slot, C confirmed —
-- two CONFIRMED rows survive), the latest-updated booking is the one
-- whose bookSlot call won (bookSlot is last-writer-wins on the slot
-- row, guarded by @Version); updated_at with an id tiebreak keeps the
-- pick total and deterministic. Slots with no resolvable holder stay
-- NULL — documented residue, never a guessed owner.
--
-- No CHECK, no index here: the uniqueness backstop is V73's partial
-- unique index (built CONCURRENTLY in its own non-transactional
-- script); this migration is a plain transactional ALTER + UPDATE.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE availability_slots
    ADD COLUMN IF NOT EXISTS held_by_booking_id uuid;

ALTER TABLE availability_slots_aud
    ADD COLUMN IF NOT EXISTS held_by_booking_id uuid;

UPDATE availability_slots s
SET held_by_booking_id = (
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
  AND s.booked = true
  AND s.held_by_booking_id IS NULL
  AND EXISTS (
    SELECT 1
    FROM bookings b
    WHERE b.provider_id = s.provider_id
      AND b.starts_at = s.starts_at
      AND b.ends_at = s.ends_at
      AND b.status IN ('CONFIRMED', 'COMPLETED')
      AND b.is_deleted = false
  );

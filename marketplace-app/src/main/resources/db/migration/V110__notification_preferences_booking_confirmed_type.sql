-- A-03 (official-compliance plan 0.6 — the dead BookingConfirmedEvent's
-- delivery, the notification leg of the owner's identity rule "an event
-- without a listener is a measured defect"; the V53/V55/V62/V63/V65/V74
-- family applied one type later): NotificationType gains BOOKING_CONFIRMED,
-- but V93's type CHECK on notification_preferences still admits only the
-- nine pre-A-03 values — a stored BOOKING_CONFIRMED preference would
-- violate the constraint at flush time (a 500 the unit-level matrix tests
-- could never see: their repository is a mock).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the single
-- source of truth for the Java side, this constraint for the SQL side) in
-- the V44 locking shape: NOT VALID (metadata-only, enforced for new rows
-- immediately). The VALIDATE step rides its OWN migration (V111) so its scan
-- runs under SHARE UPDATE EXCLUSIVE alone — the V66/V75 precedent verbatim
-- (constraint-missing-not-valid): inside one transaction the DROP/ADD
-- statements' ACCESS EXCLUSIVE lock would still be held during the
-- validation scan, blocking ordinary reads and writes for the scan's
-- duration.
--
-- Track A's Flyway range (V110-V149, parallel execution plan §5.3 over the
-- measured V105 state) — this is its first consumption.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING',
                    'BOOKING_CONFIRMED')) NOT VALID;

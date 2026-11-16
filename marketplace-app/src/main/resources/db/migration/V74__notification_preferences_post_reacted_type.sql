-- L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions layer,
-- the V53/V55/V62/V63/V65 family applied one type later): NotificationType
-- gains POST_REACTED, but V65's type CHECK on notification_preferences
-- still admits only the seven pre-L47 values — a stored POST_REACTED
-- preference would violate the constraint at flush time (a 500 the
-- unit-level matrix tests could never see: their repository is a mock).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the
-- single source of truth for the Java side, this constraint for the SQL
-- side) in the V44 locking shape: NOT VALID (metadata-only, enforced for
-- new rows immediately). The VALIDATE step rides its OWN migration (V75)
-- so its scan runs under SHARE UPDATE EXCLUSIVE alone — the V66 precedent
-- verbatim (constraint-missing-not-valid, the L36/V56+V57 Squawk
-- adoption): inside one transaction the DROP/ADD statements' ACCESS
-- EXCLUSIVE lock would still be held during the validation scan, blocking
-- ordinary reads and writes for the scan's duration.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED')) NOT VALID;

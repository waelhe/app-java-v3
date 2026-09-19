-- L45 (neighborhood community plan §5 — the moderation & reports layer,
-- the V53/V55/V62/V63 family applied one type later): NotificationType
-- gains CONTENT_MODERATED, but V63's type CHECK on notification_preferences
-- still admits only the six pre-L45 values — a stored CONTENT_MODERATED
-- preference would violate the constraint at flush time (a 500 the
-- unit-level matrix tests could never see: their repository is a mock).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the
-- single source of truth for the Java side, this constraint for the SQL
-- side) in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately). The VALIDATE step rides its OWN migration
-- (V66) so its scan runs under SHARE UPDATE EXCLUSIVE alone — the
-- L36/V56+V57 Squawk adoption (constraint-missing-not-valid): inside
-- one transaction the DROP/ADD statements' ACCESS EXCLUSIVE lock would
-- still be held during the validation scan, blocking ordinary reads and
-- writes for the scan's duration; in its own transaction the VALIDATE
-- statement's own lock level is SHARE UPDATE EXCLUSIVE, which lets the
-- shared production database keep serving traffic during the deploy.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED')) NOT VALID;

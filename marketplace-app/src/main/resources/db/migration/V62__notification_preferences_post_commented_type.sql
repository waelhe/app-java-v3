-- L42 (neighborhood community plan §5 — the posts/feed/comments layer,
-- the V53/V55 precedent applied one type later): NotificationType gains
-- POST_COMMENTED, but V55's type CHECK on notification_preferences still
-- admits only the four pre-L42 values — a stored POST_COMMENTED
-- preference would violate the constraint at flush time (a 500 the
-- unit-level matrix tests could never see: their repository is a mock).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard — NotificationType stays the
-- single source of truth for the Java side, this constraint for the SQL
-- side) in the V44 locking shape: NOT VALID (metadata-only, enforced for
-- new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so the
-- shared production database keeps serving traffic during the deploy.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED')) NOT VALID;

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

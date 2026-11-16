-- A-11 (official-compliance plan §6, wave C — C.1: the order machine's
-- notification leg): NotificationType gains ORDER_CONFIRMED,
-- ORDER_FULFILLED and ORDER_CANCELLED, and V93's type CHECK on
-- notification_preferences must widen to admit them — the V110 precedent
-- verbatim (a stored preference for a type the constraint rejects would
-- violate at flush time, a 500 the unit-level matrix tests can never see:
-- their repository is a mock).
--
-- The widened CHECK keeps the D-N7 discipline (every enumerated column
-- carries its DB-level membership guard) in the V44 locking shape: NOT
-- VALID (metadata-only, enforced for new rows immediately). The VALIDATE
-- step rides its OWN migration (V115) so its scan runs under SHARE UPDATE
-- EXCLUSIVE alone — the V66/V75/V111 precedent verbatim.
--
-- Track A's Flyway range V110-V149 — the range's fourth consumption.

ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING',
                    'BOOKING_CONFIRMED',
                    'ORDER_CONFIRMED', 'ORDER_FULFILLED', 'ORDER_CANCELLED')) NOT VALID;

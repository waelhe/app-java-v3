-- B-08 (compliance plan 0.10 — the §3.4-8 defect closed: MESSAGE_RECEIVED):
-- the tenth notification type joins the DB-side membership guard (the
-- V53/V55/V62/V63/V65/V74/V93 family, one type later): NotificationType
-- stays the single source of truth for the Java side, this constraint for
-- the SQL side (the D-N7 discipline). NOT VALID here; VALIDATE in V152 —
-- the V93/V94 split verbatim (inside one transaction the DROP/ADD
-- statements' ACCESS EXCLUSIVE lock would still be held during the
-- validation scan, blocking the ordinary reads and writes of the delivery
-- gate every notification consults).
ALTER TABLE notification_preferences DROP CONSTRAINT IF EXISTS notification_preferences_type_check;

ALTER TABLE notification_preferences
    ADD CONSTRAINT notification_preferences_type_check
    CHECK (type IN ('BOOKING_CREATED', 'PAYMENT_STATE', 'LEAD_RECEIVED',
                    'SAVED_SEARCH_MATCH', 'POST_COMMENTED',
                    'NEW_LISTING_IN_NEIGHBORHOOD', 'CONTENT_MODERATED',
                    'POST_REACTED', 'FOLLOWED_PROVIDER_NEW_LISTING',
                    'MESSAGE_RECEIVED')) NOT VALID;

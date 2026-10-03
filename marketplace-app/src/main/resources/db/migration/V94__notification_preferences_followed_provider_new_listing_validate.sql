-- W4 (yelp-level plan §5 — G21): the VALIDATE step of V93's widening of
-- the notification_preferences type CHECK to the ninth type
-- FOLLOWED_PROVIDER_NEW_LISTING — alone in its own migration so its scan
-- runs under its own statement's SHARE UPDATE EXCLUSIVE alone (the
-- V66/V75 precedent verbatim: inside V93's transaction the DROP/ADD
-- statements' ACCESS EXCLUSIVE lock would still be held during the
-- validation scan, blocking the ordinary reads and writes of the delivery
-- gate every notification consults).

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

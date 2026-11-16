-- B-08 (compliance plan 0.10): the VALIDATE step of V151's widening of the
-- notification_preferences type CHECK to the tenth type MESSAGE_RECEIVED —
-- alone in its own migration so its scan runs under its own statement's
-- SHARE UPDATE EXCLUSIVE alone (the V66/V75/V94 precedent verbatim).

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

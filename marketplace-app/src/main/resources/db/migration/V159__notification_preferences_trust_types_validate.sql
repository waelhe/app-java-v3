-- B-17 (compliance plan C.9): the VALIDATE step of V158's widening of the
-- notification_preferences type CHECK to the eleventh and twelfth types
-- (MEMBERSHIP_VERIFIED, REPORT_RESOLVED) — alone in its own migration so
-- its scan runs under its own statement's SHARE UPDATE EXCLUSIVE alone
-- (the V66/V75/V94/V152 precedent verbatim).
--
-- The lock fact the split exists for (the V66 record, root adopted):
-- Flyway runs each versioned migration in its own transaction, and a
-- transaction RETAINS every lock it acquired until it commits — so a
-- VALIDATE sharing V158's transaction would scan under the DROP/ADD
-- statements' still-held ACCESS EXCLUSIVE, blocking ordinary reads and
-- writes for the scan. Alone here, the VALIDATE statement's own lock is
-- SHARE UPDATE EXCLUSIVE — concurrent reads and writes keep flowing.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

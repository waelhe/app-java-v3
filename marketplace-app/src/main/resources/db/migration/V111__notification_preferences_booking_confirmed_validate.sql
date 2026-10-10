-- A-03 (official-compliance plan 0.6 — the dead BookingConfirmedEvent's
-- delivery): the validation step of V110's widened type CHECK, in its OWN
-- migration — the V66/V75 precedent verbatim, nothing but the VALIDATE of
-- V110's NOT VALID constraint, exactly as V75 was nothing but the VALIDATE
-- of V74's, V57 of V56's, and V66 of V65's.
--
-- The lock fact the split exists for: Flyway runs each versioned migration
-- in its own transaction, and a transaction RETAINS every lock it acquired
-- until it commits — so a VALIDATE sharing V110's transaction would scan
-- under the DROP/ADD statements' still-held ACCESS EXCLUSIVE, blocking
-- ordinary reads and writes for the scan. Alone here, the VALIDATE
-- statement's own lock is SHARE UPDATE EXCLUSIVE — concurrent reads and
-- writes keep flowing.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

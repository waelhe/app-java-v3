-- L47 (the Nextdoor-2026 completeness wave — gap #1, the reactions layer):
-- the validation step of V74's widened type CHECK, in its OWN migration —
-- the V66 precedent verbatim (the L36/V56+V57 Squawk adoption,
-- constraint-missing-not-valid), nothing but the VALIDATE of V74's NOT
-- VALID constraint, exactly as V57 was nothing but the VALIDATE of V56's
-- and V66 nothing but the VALIDATE of V65's.
--
-- The lock fact the split exists for: Flyway runs each versioned
-- migration in its own transaction, and a transaction RETAINS every lock
-- it acquired until it commits — so a VALIDATE sharing V74's transaction
-- would scan under the DROP/ADD statements' still-held ACCESS EXCLUSIVE,
-- blocking ordinary reads and writes for the scan. Alone here, the
-- VALIDATE statement's own lock is SHARE UPDATE EXCLUSIVE — concurrent
-- reads and writes keep flowing (the V66 file's own measured reasoning,
-- already proven in production by V57 and V66 alike).

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

-- The union widening: the validation step of V164's sixteen-type CHECK, in
-- its OWN migration — the V66/V75 and V110/V111 precedent verbatim, nothing
-- but the VALIDATE of V164's NOT VALID constraint, exactly as V111 was
-- nothing but the VALIDATE of V110's, V75 of V74's, V57 of V56's, and V66
-- of V65's.
--
-- The lock fact the split exists for: Flyway runs each versioned migration
-- in its own transaction, and a transaction RETAINS every lock it acquired
-- until it commits — so a VALIDATE sharing V164's transaction would scan
-- under the DROP/ADD statements' still-held ACCESS EXCLUSIVE, blocking
-- ordinary reads and writes for the scan. Alone here, the VALIDATE
-- statement's own lock is SHARE UPDATE EXCLUSIVE — concurrent reads and
-- writes keep flowing.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

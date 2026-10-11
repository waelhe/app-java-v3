-- Task 5-f: the validation step of V180's nineteen-type CHECK, in its OWN
-- migration — the V165/V159/V111/V75/V66 precedent verbatim, nothing but
-- the VALIDATE of V180's NOT VALID constraint, exactly as V165 was nothing
-- but the VALIDATE of V164's, V159 of V158's, and V111 of V110's.
--
-- The lock fact the split exists for (the V164/V165 record, root adopted):
-- Flyway runs each versioned migration in its own transaction, and a
-- transaction RETAINS every lock it acquired until it commits — so a
-- VALIDATE sharing V180's transaction would scan under the DROP/ADD
-- statements' still-held ACCESS EXCLUSIVE, blocking ordinary reads and
-- writes for the scan. Alone here, the VALIDATE statement's own lock is
-- SHARE UPDATE EXCLUSIVE — concurrent reads and writes keep flowing.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

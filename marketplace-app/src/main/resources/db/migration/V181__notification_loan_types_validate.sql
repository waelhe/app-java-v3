-- ADR-0009: the V180 widening's validation — the V159/V165 precedent
-- verbatim (the validation scan rides its OWN migration under SHARE UPDATE
-- EXCLUSIVE alone). Every stored preference row satisfies the widened
-- membership: the guard's values only grew.

ALTER TABLE notification_preferences VALIDATE CONSTRAINT notification_preferences_type_check;

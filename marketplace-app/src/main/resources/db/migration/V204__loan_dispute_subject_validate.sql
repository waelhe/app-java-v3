-- ADR-0009: the V203 checks' validation — the V167/V75/V111 precedent
-- verbatim (the validation scan rides its OWN migration so it runs under
-- SHARE UPDATE EXCLUSIVE alone; V203's rows all satisfy the pairing —
-- every existing dispute is a booking dispute with booking_id set).

ALTER TABLE disputes VALIDATE CONSTRAINT disputes_subject_type_check;
ALTER TABLE disputes VALIDATE CONSTRAINT disputes_subject_pairing_chk;

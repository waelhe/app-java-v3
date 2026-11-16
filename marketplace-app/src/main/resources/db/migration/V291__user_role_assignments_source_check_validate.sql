-- The validation half of V290's widened source CHECK, in its OWN
-- migration — the V57/V66 house form verbatim (the lock fact the split
-- exists for: a VALIDATE sharing V290's transaction would scan under the
-- re-ADD's still-held ACCESS EXCLUSIVE, blocking ordinary reads and
-- writes for the scan; alone here the statement's own lock is SHARE
-- UPDATE EXCLUSIVE — concurrent traffic keeps flowing). Validating an
-- already-valid constraint is a no-op, so the file is re-runnable.

ALTER TABLE user_role_assignments VALIDATE CONSTRAINT user_role_assignments_source_check;

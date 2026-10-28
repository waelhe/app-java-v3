-- The primary-change choreography's own mirror marker (the part-1 review
-- note on the RoleAssignmentService seam, the security carry's split of
-- agent findings row 26): V187's closed source vocabulary
-- ('BACKFILL' | 'ADMIN') cannot tell the choreography's mirrors from
-- independent administrative grants, so onPrimaryRoleChanged's retire-leg
-- revoked whatever ACTIVE row held the former primary — including an
-- independent PROVIDER grant a promote-then-demote round trip had left
-- doubling as the target's mirror, withdrawing an authority nobody
-- revoked. The widening adds 'PRIMARY_CHANGE' (rows the choreography
-- itself creates); the service's retire filter then keeps every ADMIN
-- row alive — the distinction the note requires, carried by the data.
--
-- V187's own header prescribed this exact route: "(A future source widens
-- the CHECK through the V68 NOT VALID → VALIDATE split)" — the V56+V57 /
-- V65+V66 house precedent (Squawk constraint-missing-not-valid): ADD
-- CONSTRAINT ... NOT VALID holds its ACCESS EXCLUSIVE lock only until
-- THIS transaction commits, so the validation scan runs in its OWN
-- migration (V291) under SHARE UPDATE EXCLUSIVE — ordinary reads and
-- writes keep flowing during the scan.
--
-- DROP + ADD (not a value-list ALTER): PostgreSQL replaces a CHECK by
-- dropping and re-adding it; the NOT VALID half lands here, the VALIDATE
-- half in V291 (validating an already-valid constraint is a no-op —
-- re-runnable after repair, the V57 letter).

ALTER TABLE user_role_assignments DROP CONSTRAINT user_role_assignments_source_check;
ALTER TABLE user_role_assignments
    ADD CONSTRAINT user_role_assignments_source_check
    CHECK (source IN ('BACKFILL', 'ADMIN', 'PRIMARY_CHANGE')) NOT VALID;

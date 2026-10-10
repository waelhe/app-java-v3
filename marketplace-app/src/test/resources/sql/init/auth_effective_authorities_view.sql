-- The sql.init test schemas' equivalent of V183's
-- auth_effective_authorities view.
--
-- The seven login-chain integration tests that load ONLY
-- V13__authorization_security.sql through spring.sql.init carry the
-- auth_* tables and nothing else — there is no users row and no
-- user_role_assignments table in those schemas (the assignment model's
-- own tables belong to the Flyway-managed schema, proven by the
-- dedicated migration test). THIS file is their faithful equivalent of
-- the effective-authority source: the view reduced to its auth-only
-- leg, which is exactly what the union computes when no assignment
-- rows exist — the login chain's behavior stays byte-identical in
-- those slices while the multi-role union itself is guarded by
-- UserRoleMigrationIntegrationTest on the real migrated schema.
--
-- Test resource only — never applied in production (Flyway owns the
-- real view; V183 is its single birth, never edited afterwards).

CREATE OR REPLACE VIEW auth_effective_authorities AS
    SELECT username, authority FROM auth_authorities;

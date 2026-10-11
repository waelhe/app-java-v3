-- Phase 1 (the unified plan §10 — الهوية والأدوار والمحلية والثقة) — D-03
-- (§3.1 P0 gap «UserRole أحادي», §4.3 ownership row «هوية الحساب الأساس →
-- marketplace-identity → منح أدوار متعددة بترحيل آمن»): the multi-role
-- assignment — one account holds APPROVED role combinations safely
-- (a person can be a community member AND a store owner AND a service
-- provider at once), the safe-for-migration model the plan mandates
-- before any endpoint expansion.
--
-- THE ROLE VOCABULARY STAYS CLOSED (the task's own constraint — «لا توسيع
-- enum الآن»): the CHECK carries exactly the values users.role has
-- carried since V1 ('CONSUMER','PROVIDER','ADMIN'). No new role kind is
-- invented here; the ASSIGNMENT model is what is new — one user may now
-- hold SEVERAL of the same three roles as independent rows.
--
-- users.role IS DELIBERATELY UNTOUCHED (the task's Phase-1 letter): the
-- column keeps its NOT NULL + CHECK, every read and write path keeps
-- working (UserService's two-store choreography S2/N4/N6 continues to
-- maintain it and its login-side auth_authorities projection), and the
-- assignment model becomes the operative multi-role source at the
-- security boundary GRADUALLY (V183's effective-authorities view is the
-- only wiring this wave performs). No column is nulled, dropped, or
-- widened.
--
-- THE SAFE BACKFILL («الحسابات القائمة تحفظ وصولها بلا توسيع صلاحيات»):
-- every LIVE account (users.is_deleted = FALSE) receives exactly ONE
-- active assignment mirroring ITS OWN users.role — granted_at = the
-- account's own birthday (COALESCE created_at, now(); the role existed
-- since the account did — honest provenance), granted_by = NULL (the
-- migration is a system act, not an administrator's). The insert reads
-- users.role and nothing else: a backfilled assignment can never exceed
-- the authority the account already holds. Soft-deleted accounts
-- (is_deleted = TRUE — withdrawn memberships) are deliberately NOT
-- backfilled: there is no access left to preserve. Pseudonymized
-- accounts (I7 — is_deleted stays FALSE, the row persists by the
-- Art. 17(3)(b) decision) ARE backfilled, preserving their recorded
-- role with zero access effect (their auth_users row is already gone —
-- the V183 union cannot grant anything a login row no longer backs).
--
-- Cross-module discipline: NONE needed — users is THIS module's own
-- table, so the FK is a real intra-module REFERENCES (the V178
-- delegated_urgent_sources precedent for a same-unit FK), giving the
-- assignment rows honest referential integrity. users rows are never
-- physically deleted (I7 — pseudonymization keeps them; self-deletion
-- rides pseudonymizeAccount), so the FK never blocks the existing
-- erasure flows.
--
-- THE UNIQUE + INDEX SHAPE (the task's column/index spec, the V93/V177
-- house forms):
-- (a) uq_user_role_assignments_one_active — ONE ACTIVE assignment per
--     (user, role): the partial unique index on the revoked_at IS NULL
--     AND is_deleted = FALSE predicate. The service's duplicate-active
--     read comes first (the clean 409); this index is the
--     concurrent-insert backstop (23505 — the two-layer model verbatim).
--     A revoked (or soft-deleted) row frees the (user, role) pair — a
--     re-grant inserts a FRESH row (the history stays, the audit
--     record).
-- (b) idx_user_role_assignments_user_active — the (user_id) active
--     read with the L32 deterministic order (granted_at DESC, id DESC —
--     the complete sort key, no wobbly pages).
-- (c) idx_user_role_assignments_role_active — the ACTIVE-ROLE scan
--     (who holds role X — the future discovery/trust joins and
--     administrative queries land here).
--
-- All BaseEntity columns present from day one (the V25/V32 lesson); the
-- Envers mirror follows the V24 convention (the V33 lesson) — all
-- mirror columns NULLABLE (a DEL revision carries only id/rev/revtype).
--
-- The table is born EMPTY (the backfill INSERT is the first writer and
-- the FK guarantees it writes only existing users): the NOT VALID →
-- VALIDATE pair rides the documented empty-table exception (V177's own
-- letter — CREATE + ADD NOT VALID + VALIDATE commit together, the
-- VALIDATE scan sees only the just-inserted backfill rows, and no
-- concurrent session can see the table before this transaction closes;
-- the explicit two-step form is kept so the house vocabulary stays
-- visible).
--
-- Checksum registered in migration-checksums.properties in this same PR
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE user_role_assignments (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users (id),
    role         VARCHAR(30) NOT NULL,
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by   UUID,
    source       VARCHAR(50) NOT NULL,
    revoked_at   TIMESTAMPTZ,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The closed role vocabulary — exactly V1's users.role CHECK list (the
-- enum's SQL-side twin; no widening in this phase).
ALTER TABLE user_role_assignments
    ADD CONSTRAINT user_role_assignments_role_check
    CHECK (role IN ('CONSUMER', 'PROVIDER', 'ADMIN')) NOT VALID;

-- The closed source vocabulary — the row's two legitimate writers today:
-- the migration's own backfill and the administrative grant surface.
-- (A future source widens the CHECK through the V68 NOT VALID → VALIDATE
-- pair on the live table — never an inline edit of this file.)
ALTER TABLE user_role_assignments
    ADD CONSTRAINT user_role_assignments_source_check
    CHECK (source IN ('BACKFILL', 'ADMIN')) NOT VALID;

-- The empty-table exception (see the header): VALIDATE in the same
-- transaction scans only this migration's own backfill rows.
ALTER TABLE user_role_assignments
    VALIDATE CONSTRAINT user_role_assignments_role_check;

ALTER TABLE user_role_assignments
    VALIDATE CONSTRAINT user_role_assignments_source_check;

-- One ACTIVE assignment per (user, role) — the service's idempotent
-- duplicate read first, this partial unique index the concurrent-insert
-- backstop; revoke (or the soft delete) frees the pair for a legitimate
-- re-grant.
CREATE UNIQUE INDEX uq_user_role_assignments_one_active
    ON user_role_assignments (user_id, role)
    WHERE revoked_at IS NULL AND is_deleted = FALSE;

-- The (user_id) active read, deterministic order (the L32 shape).
CREATE INDEX idx_user_role_assignments_user_active
    ON user_role_assignments (user_id, granted_at DESC, id DESC)
    WHERE revoked_at IS NULL AND is_deleted = FALSE;

-- The active-role scan (who holds role X).
CREATE INDEX idx_user_role_assignments_role_active
    ON user_role_assignments (role)
    WHERE revoked_at IS NULL AND is_deleted = FALSE;

-- THE SAFE BACKFILL — one active assignment per live account, mirroring
-- exactly its own users.role (see the header's no-expansion proof).
INSERT INTO user_role_assignments
        (id, user_id, role, granted_at, granted_by, source)
SELECT gen_random_uuid(),
       u.id,
       u.role,
       COALESCE(u.created_at, now()),
       NULL,
       'BACKFILL'
FROM users u
WHERE u.is_deleted = FALSE;

-- Envers mirror (V24 convention — all columns NULLABLE).
CREATE TABLE user_role_assignments_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    user_id      UUID,
    role         VARCHAR(30),
    granted_at   TIMESTAMPTZ,
    granted_by   UUID,
    source       VARCHAR(50),
    revoked_at   TIMESTAMPTZ,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

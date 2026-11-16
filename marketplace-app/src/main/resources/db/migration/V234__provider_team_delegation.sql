-- W5b (the journeys register's J29 row — تفويض فريق مزود الخدمة بصلاحيات
-- ونطاق): the provider team delegation — the register's own CONFIRMED GAP
-- wording: «تفويض فريق/فرع بصلاحيات ونطاق — غير منفذ ← W5/W11». One table,
-- one migration: the delegation IS the concern (who may act for a provider
-- beside its owner).
--
-- Attribution rules (the V96 business-page precedent, the same family —
-- this table is the provider module's fourth declared surface):
--   * Full BaseEntity columns from day one (the V25/V32 lesson): is_deleted,
--     version, created_by/at, updated_by/at.
--   * CHECKs in the V44/V84 shape: NOT VALID then VALIDATE in the SAME
--     script — a new table is empty, so the validation scan is metadata-only.
--   * Partial unique index in the V70/V64 shape, one family deeper: the
--     live-membership predicate holds over status='ACTIVE' AND
--     is_deleted=FALSE — a REVOKED row must never block a later re-add
--     (the one-live-membership law below).
--   * The _aud mirror in the V24 shape: all columns nullable (a DEL revision
--     carries (id, rev, revtype) alone).
--   * member_user_id is a PLAIN UUID — the identity module boundary: the
--     provider module never references users(id) physically (the same
--     boundary provider_profiles.user_id itself keeps — V96's own
--     provider_id reference pattern applies only to the module's OWN
--     provider_profiles table).
--
-- The delegation's own laws (the service layer enforces the gate order;
-- the schema enforces the invariants):
--   * ONE live membership per member per provider — the partial unique
--     index uq_provider_team_one_live; revocation frees the slot (a second
--     ACTIVE row is a data defect, never a second team seat).
--   * The OWNER needs no delegation — the service refuses member==owner
--     with 409; the owner's authority is the profile's user_id, never a
--     membership row (no seeding an owner-shaped member is ever needed).
--   * Two roles only, both write-capable on the declared surfaces: MANAGER
--     and EDITOR (the register's «بصلاحيات ونطاق» first cut — a read-only
--     VIEWER class is a deliberate later extension, not a silent default).
--   * Two states only: ACTIVE and REVOKED — revocation is the single
--     exit (the urgent-alert withdrawal shape: the row and its Envers
--     trail stay, the authority ends).

CREATE TABLE provider_team_members (
    id              UUID PRIMARY KEY,
    provider_id     UUID NOT NULL REFERENCES provider_profiles (id),
    member_user_id  UUID NOT NULL,
    role            VARCHAR(20) NOT NULL,
    status          VARCHAR(20) NOT NULL,
    added_by        VARCHAR(200),
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The delegation's own vocabulary: two write-capable roles only.
    CONSTRAINT chk_provider_team_members_role
        CHECK (role IN ('MANAGER', 'EDITOR')) NOT VALID,
    -- The delegation's own lifecycle: ACTIVE until the owner revokes.
    CONSTRAINT chk_provider_team_members_status
        CHECK (status IN ('ACTIVE', 'REVOKED')) NOT VALID
);

ALTER TABLE provider_team_members VALIDATE CONSTRAINT chk_provider_team_members_role;
ALTER TABLE provider_team_members VALIDATE CONSTRAINT chk_provider_team_members_status;

-- One LIVE membership per member per provider (the V70 identity shape, one
-- predicate deeper): a REVOKED or soft-deleted row never blocks a re-add —
-- the slot is the LIVE delegation, not the row's history.
CREATE UNIQUE INDEX uq_provider_team_one_live
    ON provider_team_members (provider_id, member_user_id)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;

-- The owner's team read: the live roster in arrival order.
CREATE INDEX idx_provider_team_members_provider_live
    ON provider_team_members (provider_id)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;

-- The authorization seam's own read: is this user a live member HERE? The
-- (member_user_id, provider_id) shape serves the existsBy probe the gates
-- run on every delegated write.
CREATE INDEX idx_provider_team_members_member_live
    ON provider_team_members (member_user_id, provider_id)
    WHERE status = 'ACTIVE' AND is_deleted = FALSE;

CREATE TABLE provider_team_members_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    provider_id     UUID,
    member_user_id  UUID,
    role            VARCHAR(20),
    status          VARCHAR(20),
    added_by        VARCHAR(200),
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

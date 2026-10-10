-- ADR-0001 (plan #536 §Phase 1 — D-02/D-03): multi-role identity +
-- verification credentials.
--
-- 1) user_roles — the account's role SET, the source of truth. The legacy
--    scalar users.role column stays the primary-role mirror (its
--    deterministic projection, never written independently): the faithful
--    legacy copy below grants each account EXACTLY its current role — no
--    privilege elevation, no inference («ترحيل إضافي للبيانات القديمة بلا
--    رفع صلاحيات تلقائي»).
-- 2) verification_credentials — the evidence an account submits per
--    credential type (identity / residence / business ownership /
--    professional qualification / official publisher) with its own reviewed
--    lifecycle. Deciding a credential NEVER grants or removes a role — the
--    elevation path is an administrative, human act on the roles stores
--    (ADR-0001: identity ≠ roles ≠ credentials).
-- 3) The _aud mirror in the V24 shape (all columns nullable — the V33
--    lesson) for the @Audited credential entity. user_roles is an element
--    collection of the @Audited User, not its own entity: role history is
--    carried by the users_aud primary-mirror column + the audit-log lines
--    every role command writes.
--
-- Numbering: V171 — the next free versioned slot on main (V170 landed
-- with #538).
--
-- Checksum registered in migration-checksums.properties in this same
-- unit (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- 1) The multi-role truth table.
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role    VARCHAR(30) NOT NULL,
    PRIMARY KEY (user_id, role),
    CONSTRAINT fk_user_roles_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_user_roles_role
        CHECK (role IN ('CONSUMER', 'PROVIDER', 'ADMIN'))
);

-- The legacy projection: each account receives EXACTLY its current scalar
-- role — one row, the same value, zero elevation.
INSERT INTO user_roles (user_id, role)
SELECT id, role
FROM users
ON CONFLICT (user_id, role) DO NOTHING;

-- 2) The verification credential half.
CREATE TABLE verification_credentials (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL,
    credential_type VARCHAR(40) NOT NULL,
    status         VARCHAR(20) NOT NULL,
    evidence_uri   VARCHAR(1024),
    notes          VARCHAR(2000),
    decision_notes VARCHAR(2000),
    reviewed_by    VARCHAR(200),
    reviewed_at    TIMESTAMPTZ,
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    version        BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_verification_credentials_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_verification_credentials_type
        CHECK (credential_type IN (
            'IDENTITY', 'RESIDENCE', 'BUSINESS_OWNERSHIP',
            'PROFESSIONAL_QUALIFICATION', 'OFFICIAL_PUBLISHER'
        )),
    CONSTRAINT chk_verification_credentials_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'))
);

-- The account's own history read (the /mine surface).
CREATE INDEX idx_verification_credentials_user
    ON verification_credentials (user_id, created_at DESC)
    WHERE is_deleted = FALSE;

-- The admin review queue's status filter.
CREATE INDEX idx_verification_credentials_status
    ON verification_credentials (status, created_at DESC)
    WHERE is_deleted = FALSE;

-- 3) The Envers audit mirror (the V24 convention; the V33 lesson).
CREATE TABLE verification_credentials_aud (
    id             UUID NOT NULL,
    rev            INTEGER NOT NULL,
    revtype        SMALLINT,
    user_id        UUID,
    credential_type VARCHAR(40),
    status         VARCHAR(20),
    evidence_uri   VARCHAR(1024),
    notes          VARCHAR(2000),
    decision_notes VARCHAR(2000),
    reviewed_by    VARCHAR(200),
    reviewed_at    TIMESTAMPTZ,
    is_deleted     BOOLEAN,
    version        BIGINT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

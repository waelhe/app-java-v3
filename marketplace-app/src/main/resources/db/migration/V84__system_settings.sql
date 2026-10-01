-- W0 (yelp-level-plan §5 — the control layer): the platform settings table.
-- The first control of its kind in the codebase: rows editable from the
-- administrative surface without a redeploy, read through a cached port in
-- W1 (the review-creation gate reads reviews.mode).
--
-- Attribution rules (the governing plan's §1.4):
--   * Full BaseEntity columns from day one (the V25/V32 lesson — every
--     column is born with the table, never added later).
--   * CHECK in the V44 shape: NOT VALID (metadata-only index — an
--     immediate constraint for new rows) then VALIDATE in its own
--     transaction (the V66 lesson: inside one transaction the held lock
--     stays held until commit).
--   * The unique index over the key is GLOBAL: the key is the setting's
--     identity and is never recycled — the V70 counter-decision
--     (uq_categories_code: an identity is not released by soft deletion,
--     so a recreated row can never sit beside stale data carrying the
--     same key).
--   * The system_settings_aud mirror in the V24 shape: all columns
--     nullable (a DEL revision carries (id, rev, revtype) alone — the
--     V24 lesson, addressed again in V54).
--   * The JSONB value through Hibernate's official mapping (V48
--     amenities / V54 criteria — @JdbcTypeCode(SqlTypes.JSON)) — the
--     polymorphic shape: string/number/boolean/object as the W1+
--     policies need.
--   * The seed bypasses Envers by nature (the geo D-E11 and V70
--     categories precedent: the mirror records the first real
--     administrative write).
--
-- Access rights: /api/v1/admin/** => hasRole(ADMIN) in the SecurityConfig
-- chain (L30) + the class level on AdminController + the service level —
-- three layers.

CREATE TABLE system_settings (
    id          UUID PRIMARY KEY,
    setting_key VARCHAR(100) NOT NULL,
    setting_value JSONB NOT NULL,
    description VARCHAR(500),
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The key shape: a lowercase dotted identifier (reviews.mode / reviews.organic.daily-cap).
    CONSTRAINT chk_system_settings_key
        CHECK (setting_key = lower(setting_key)
           AND length(setting_key) BETWEEN 1 AND 100
           AND setting_key ~ '^[a-z][a-z0-9-]*(\.[a-z0-9-]+)*$')
        NOT VALID
);

ALTER TABLE system_settings VALIDATE CONSTRAINT chk_system_settings_key;

-- The key is the setting's identity (global, no soft-delete predicate — the V70 identity decision).
CREATE UNIQUE INDEX uq_system_settings_key
    ON system_settings (setting_key);

-- The seed: the review gate stays exactly in its current mode (zero visible
-- behavior change — W0's acceptance criterion). A fixed id, read from the seed itself.
INSERT INTO system_settings (id, setting_key, setting_value, description)
VALUES ('71717171-7171-4171-8171-717171717171',
        'reviews.mode',
        '"VERIFIED_ONLY"'::jsonb,
        'Review-creation gate: VERIFIED_ONLY | OPEN | HYBRID (yelp plan 4.1)');

-- Envers audit history (V24 convention; all columns nullable — a DEL
-- revision carries only (id, rev, revtype)). The seed above bypasses
-- Envers by nature; the mirror records the first real admin write.
CREATE TABLE system_settings_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    setting_key   VARCHAR(100),
    setting_value JSONB,
    description   VARCHAR(500),
    is_deleted    BOOLEAN,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

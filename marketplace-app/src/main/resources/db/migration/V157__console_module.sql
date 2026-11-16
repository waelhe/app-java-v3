-- B-15 (compliance plan C.5 — the platform identity's «الأنظمة العرضية»
-- row: the advanced console): the feature flags and the remote config
-- tables — the OPERATIONAL half of the Boot external-config design
-- (the static half is ConsoleProperties, bound at boot; these rows are
-- read at request time — the C.10 measured limit verbatim: Boot's
-- configuration governs the static at boot EXCLUSIVELY, the operational
-- is DATA). The console's ceiling is the identity statement's own
-- active limit: «اللوحة إعداد وتشغيل ومحتوى وسياسات؛ والقدرة غير
-- الموجودة كوداً تبقى تطويراً» — a flag/config row names or calibrates
-- an EXISTING capability; it never creates one.
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson) — the
--     auditing fields ARE the console's change-history read's own
--     source (the Data JPA auditing reference's fields: who, when).
--   * Partial unique indexes in the V70 shape: one LIVE row per key —
--     a soft-deleted (retired) flag/config never blocks its own
--     re-registration.
--   * The _aud mirrors in the V24 shape: all columns nullable.
--   * No CHECK constraints: the rows carry no stored vocabulary (the
--     key namespace and the value grammar belong to their readers).
--
-- Numbering: V157 — Track B's range (V150-V189).

CREATE TABLE feature_flags (
    id          UUID PRIMARY KEY,
    key_name    VARCHAR(200) NOT NULL,
    description TEXT,
    enabled     BOOLEAN NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One LIVE flag per key: the service's request-time read is a point
-- lookup (findByKey), and the in-flight duplicate registration loses
-- loudly here (the V5/V150 idempotency discipline's polite face is the
-- service's own 409).
CREATE UNIQUE INDEX uq_feature_flags_key
    ON feature_flags (key_name)
    WHERE is_deleted = FALSE;

CREATE TABLE remote_config_values (
    id          UUID PRIMARY KEY,
    key_name    VARCHAR(200) NOT NULL,
    value       TEXT NOT NULL,
    description TEXT,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_remote_config_values_key
    ON remote_config_values (key_name)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every registration (ADD),
-- flip/revision (MOD), and retirement (DEL) leaves a revision — the
-- change history the console's audit read surfaces through the row's
-- own auditing fields.
CREATE TABLE feature_flags_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    key_name    VARCHAR(200),
    description TEXT,
    enabled     BOOLEAN,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE remote_config_values_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    key_name    VARCHAR(200),
    value       TEXT,
    description TEXT,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- V175 — Stage 9 (plan D-11, ADR-0005): the console planning record —
-- the version-tagged planning sections the operator manages WITHOUT a
-- software release (the plan's «التغيير من اللوحة لا الكود»), every
-- revision immutable and audited, the rollback a REPUBLISH of the target
-- payload (the append-only design: the pointer never mutates, the trail
-- never rewrites), and the payload validated against the SUPPORTED
-- component vocabulary + the catalog's category dictionary BEFORE any
-- write («لا كود قابل للتنفيذ أو HTML حر أو أجزاء استعلام في الإعدادات»).

CREATE TABLE console_planning_revisions (
    id           UUID PRIMARY KEY,
    revision_no  INT NOT NULL UNIQUE,
    payload      JSONB NOT NULL,
    note         VARCHAR(500),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The Envers audit mirror (the V24 convention verbatim; the payload's
-- JSONB shape rides the audit snapshot — every publish/rollback leaves
-- the full row).
CREATE TABLE console_planning_revisions_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    revision_no  INT,
    payload      JSONB,
    note         VARCHAR(500),
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

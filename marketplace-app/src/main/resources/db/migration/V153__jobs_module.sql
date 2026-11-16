-- B-12 (compliance plan C.2 — the jobs module): the market's employment
-- vertical. The module follows the reviews pattern (the house's strongest
-- self-contained shape): two tables, no cross-module foreign keys beyond
-- the platform's own users.id space (which stays un-FK'd per the A1
-- measured convention — reviews.provider_id carries the same seam with no
-- FK, the users table is identity's garden).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECKs in the V44 locking shape: NOT VALID + inline VALIDATE —
--     both tables are brand-new and empty (the V52/V58 precedent for new
--     tables; the V56/V66 split exists for tables with live rows).
--   * Partial unique index in the V70/V64 shape: the application identity
--     over the live rows only (WHERE is_deleted = FALSE) — a withdrawn
--     application can be re-submitted as a fresh row without colliding
--     with its own tombstone.
--   * The _aud mirrors in the V24 shape: all columns nullable.
--   * The salary block's all-or-nothing + bounds discipline lives in BOTH
--     the factory (the loud guard) and the cross-column CHECK (the honest
--     backstop — the payments_refund_amountCents precedent of guarding the
--     shape at the table too).
--
-- Numbering: V153 — Track B's range (V150-V189); V150/V151/V152 are the
-- line's earlier migrations.

CREATE TABLE jobs (
    id                    UUID PRIMARY KEY,
    employer_id           UUID NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    description           TEXT NOT NULL,
    employment_type       VARCHAR(20) NOT NULL,
    workplace_type        VARCHAR(20) NOT NULL,
    city                  VARCHAR(100) NOT NULL,
    district              VARCHAR(100),
    salary_min_cents      BIGINT,
    salary_max_cents      BIGINT,
    salary_currency       VARCHAR(3),
    status                VARCHAR(20) NOT NULL,
    application_deadline  TIMESTAMPTZ,
    is_deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    version               BIGINT NOT NULL DEFAULT 0,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The stored vocabularies' membership guards (the enum mirrors — a future
-- value widens by migration, never by rebuilding the type).
ALTER TABLE jobs ADD CONSTRAINT chk_jobs_employment_type
    CHECK (employment_type IN ('FULL_TIME','PART_TIME','CONTRACT','INTERNSHIP','VOLUNTEER')) NOT VALID;
ALTER TABLE jobs VALIDATE CONSTRAINT chk_jobs_employment_type;

ALTER TABLE jobs ADD CONSTRAINT chk_jobs_workplace_type
    CHECK (workplace_type IN ('ONSITE','REMOTE','HYBRID')) NOT VALID;
ALTER TABLE jobs VALIDATE CONSTRAINT chk_jobs_workplace_type;

ALTER TABLE jobs ADD CONSTRAINT chk_jobs_status
    CHECK (status IN ('ACTIVE','CLOSED')) NOT VALID;
ALTER TABLE jobs VALIDATE CONSTRAINT chk_jobs_status;

-- The salary block: all three together or none, the floor under the
-- ceiling, non-negative (the factory's own guard, held at the table too).
ALTER TABLE jobs ADD CONSTRAINT chk_jobs_salary_block
    CHECK (
        (salary_min_cents IS NULL AND salary_max_cents IS NULL AND salary_currency IS NULL)
        OR
        (salary_min_cents IS NOT NULL AND salary_max_cents IS NOT NULL AND salary_currency IS NOT NULL
         AND salary_min_cents >= 0 AND salary_min_cents <= salary_max_cents)
    ) NOT VALID;
ALTER TABLE jobs VALIDATE CONSTRAINT chk_jobs_salary_block;

-- The public board's read: the ACTIVE surface, newest-posted first with
-- the complete (created_at, id) key keeping the page boundary stable
-- (D-N5, the listing_favorites index discipline).
CREATE INDEX idx_jobs_board
    ON jobs (status, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The employer's own management list.
CREATE INDEX idx_jobs_employer
    ON jobs (employer_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every post (ADD), close (MOD)
-- and withdraw (DEL) leaves a revision.
CREATE TABLE jobs_aud (
    id                    UUID NOT NULL,
    rev                   INTEGER NOT NULL,
    revtype               SMALLINT,
    employer_id           UUID,
    title                 VARCHAR(200),
    description           TEXT,
    employment_type       VARCHAR(20),
    workplace_type        VARCHAR(20),
    city                  VARCHAR(100),
    district              VARCHAR(100),
    salary_min_cents      BIGINT,
    salary_max_cents      BIGINT,
    salary_currency       VARCHAR(3),
    status                VARCHAR(20),
    application_deadline  TIMESTAMPTZ,
    is_deleted            BOOLEAN,
    version               BIGINT,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ,
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE job_applications (
    id             UUID PRIMARY KEY,
    job_id         UUID NOT NULL REFERENCES jobs (id),
    seeker_id      UUID NOT NULL,
    cover_message  TEXT NOT NULL,
    status         VARCHAR(20) NOT NULL,
    is_deleted     BOOLEAN NOT NULL DEFAULT FALSE,
    version        BIGINT NOT NULL DEFAULT 0,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE job_applications ADD CONSTRAINT chk_job_applications_status
    CHECK (status IN ('NEW','REVIEWED','ACCEPTED','REJECTED')) NOT VALID;
ALTER TABLE job_applications VALIDATE CONSTRAINT chk_job_applications_status;

-- One LIVE application per (job, seeker) — the partial-unique identity
-- (the V70 shape): the in-flight double-submit loses loudly here (the
-- V5/V150 idempotency discipline), and a withdrawn application never
-- blocks its own re-submission.
CREATE UNIQUE INDEX uq_job_applications_job_seeker
    ON job_applications (job_id, seeker_id)
    WHERE is_deleted = FALSE;

-- The employer's inbox read for one job, newest first.
CREATE INDEX idx_job_applications_job
    ON job_applications (job_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The seeker's own list across every job.
CREATE INDEX idx_job_applications_seeker
    ON job_applications (seeker_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

CREATE TABLE job_applications_aud (
    id             UUID NOT NULL,
    rev            INTEGER NOT NULL,
    revtype        SMALLINT,
    job_id         UUID,
    seeker_id      UUID,
    cover_message  TEXT,
    status         VARCHAR(20),
    is_deleted     BOOLEAN,
    version        BIGINT,
    created_by     VARCHAR(200),
    created_at     TIMESTAMPTZ,
    updated_by     VARCHAR(200),
    updated_at     TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- V176 — Stage 10 (plan D-13, ADR-0006): the ordering evaluation set —
-- the LOCAL labeled asset the plan's own gate demands before any learned
-- model («خط أساس حتمي + مجموعة موسومة ثم قرار LTR»). The cases pair a
-- query with a listing's labeled relevance (the graded 0–3 scale), the
-- runner scores the deterministic ranking against them (NDCG@10, MRR),
-- and the report is the evidence the learned ordering stays BEHIND until
-- it beats this baseline on the same set.

CREATE TABLE ordering_eval_cases (
    id          UUID PRIMARY KEY,
    query       VARCHAR(200) NOT NULL,
    listing_id  UUID NOT NULL,
    relevance   INT NOT NULL CHECK (relevance IN (0, 1, 2, 3)),
    tags        VARCHAR(200) NOT NULL DEFAULT '',
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ordering_eval_cases_query_listing UNIQUE (query, listing_id)
);
CREATE INDEX idx_ordering_eval_cases_query ON ordering_eval_cases (query);

-- The Envers audit mirror (the V24 convention verbatim) — the eval set
-- is a measured asset: its changes leave revisions.
CREATE TABLE ordering_eval_cases_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    query       VARCHAR(200),
    listing_id  UUID,
    relevance   INT,
    tags        VARCHAR(200),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

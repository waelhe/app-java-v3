-- B-19 (compliance plan C.11 — محرك قواعد إشراف تلقائية): the
-- automatic moderation rules table — DATA rows standing above the
-- existing reports machine (L45). The rule's whole shape is the
-- report's own measured axes: WHICH target type, WHICH reason, and
-- HOW MANY distinct reporters (the community's signal) fire the
-- engine's single measured verb — the automatic HIDE_CONTENT: hide
-- the content, alert its author, resolve every OPEN report on the
-- target, and publish the reporters' adjudication facts, all inside
-- the report-creation transaction (the C.11 mandate — the evaluation
-- is a request-time read riding the caller's unit of work, never a
-- boot-time conditional).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity column set from day one (the V25/V32 lesson) —
--     the auditing fields ARE the rules' change history (who
--     registered, revised, toggled, retired — and when).
--   * CHECKs in the V64 shape (the pair born with the table): the
--     target_type and reason vocabularies are the report machine's
--     OWN closed words (the same guards content_reports carries), so
--     a rule can never name an axis the machine cannot receive; the
--     threshold floor is >= 1 — a rule that fires on zero reports is
--     not a rule.
--   * Partial unique in the V70/V157/V160 shape: one LIVE row per
--     (target_type, reason) — a retired (soft-deleted) rule never
--     blocks its own re-registration; the enabled toggle is NOT part
--     of the identity (a paused rule holds its slot until retired).
--   * The _aud mirror in the V24 shape: all columns nullable.
--
-- The VALUES are data managed through the console's sibling
-- discipline — the administrative CRUD surface, never migrations
-- (the V70 dictionary discipline verbatim).
--
-- Numbering: V161 — Track B's range (V150-V189).

CREATE TABLE moderation_rules (
    id          UUID PRIMARY KEY,
    target_type VARCHAR(20) NOT NULL,
    reason      VARCHAR(30) NOT NULL,
    threshold   INTEGER NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_moderation_rules_target_type
        CHECK (target_type IN ('POST', 'COMMENT', 'REVIEW')) NOT VALID,
    CONSTRAINT chk_moderation_rules_reason
        CHECK (reason IN ('SPAM', 'HARASSMENT', 'INAPPROPRIATE', 'OTHER')) NOT VALID,
    CONSTRAINT chk_moderation_rules_threshold
        CHECK (threshold >= 1) NOT VALID
);

-- A table born in this migration is empty by construction: the
-- VALIDATE scan is free, so the pair rides the same file (the V64
-- small-table measured precedent — the V66/V75 split exists for
-- EXISTING tables under live traffic).
ALTER TABLE moderation_rules VALIDATE CONSTRAINT chk_moderation_rules_target_type;
ALTER TABLE moderation_rules VALIDATE CONSTRAINT chk_moderation_rules_reason;
ALTER TABLE moderation_rules VALIDATE CONSTRAINT chk_moderation_rules_threshold;

-- One LIVE rule per (target_type, reason) — the duplicate
-- registration's backstop; the service's own 409 is the polite face
-- (the 23505⇒409 house translation). The index doubles as the
-- evaluation lookup's point-read index (the engine's ONE rule query
-- per report creation).
CREATE UNIQUE INDEX uq_moderation_rules_target_type_reason
    ON moderation_rules (target_type, reason)
    WHERE is_deleted = FALSE;

-- Envers audit history (V24 convention): every registration (ADD),
-- threshold revision or pause/resume (MOD), and retirement (DEL)
-- leaves a revision — the change-history trail the auditing fields
-- surface.
CREATE TABLE moderation_rules_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    target_type VARCHAR(20),
    reason      VARCHAR(30),
    threshold   INTEGER,
    enabled     BOOLEAN,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

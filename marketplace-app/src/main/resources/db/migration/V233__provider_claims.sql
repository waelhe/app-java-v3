-- V233 — the provider business-claim cycle (J14 «مطالبة صاحب العمل
-- بالملف» — the journeys register's confirmed gap: «لا دورة مطالبة/تحقق/
-- اعتراض مقيسة»). The Yelp-shaped page lifecycle the platform was
-- missing: a business page can be PRE-SEEDED by the platform with NO
-- owner (user_id NULL), a member CLAIMS it, an administrator reviews the
-- claim, and APPROVAL hands the page's ownership over (provider_profiles.
-- user_id = claimant) — the measured cycle the J14 acceptance journey
-- rides end to end.
--
--   * provider_claims — the claim rows: WHO claims WHICH page, HOW the
--     reviewer reaches them back (contact_channel), the claim's own
--     words (note), the guarded state machine
--     (PENDING → APPROVED / REJECTED / WITHDRAWN — nothing else), and
--     the decision's attribution (decided_by/decision_note/decided_at:
--     the administrator's verdict on review, the CLAIMANT's own hand on
--     withdrawal).
--   * provider_profiles.claimed_at — the ownership handover's own clock:
--     set when an APPROVED claim links the claimant, NULL while the page
--     stays unclaimed (the platform's pre-seed) and on every
--     owner-registered page (the POST /providers self-onboarding — a
--     claim never happened there).
--
-- Attribution rules (the house shapes, verbatim from this module's own
-- precedents):
--   * provider_id — the in-module aggregate reference with a REAL FK
--     (the V96 business_hours / provider_services shape: a claim and its
--     page are one aggregate inside the provider module's boundary).
--   * claimant_user_id — a PLAIN UUID, no FK (the V32/V48/V83
--     cross-module discipline: the identity module owns the users table;
--     the provider module references its ids, never its rows).
--   * Full BaseEntity columns from day one (the V25/V32 lesson): 
--     is_deleted, version, created_by/at, updated_by/at.
--   * CHECKs in the V44/V96 shape: NOT VALID then VALIDATE in the SAME
--     script — the table is NEW and empty, so the validation scan is
--     metadata-only (a plain CREATE INDEX needs no CONCURRENTLY for the
--     same reason).
--   * Partial unique index in the V70/V96 shape: ONE LIVE CLAIM PER
--     PAGE — the identity predicate holds over the PENDING live rows
--     only. A REJECTED/WITHDRAWN claim no longer blocks (the claimant
--     may submit a fresh one), and the index is the RACE BACKSTOP under
--     the service's own pre-write guard (two concurrent submits on the
--     same page: exactly one INSERT survives).
--   * The _aud mirror in the V24/V96 shape: all columns nullable (a DEL
--     revision carries (id, rev, revtype) alone) — the Envers trail is
--     the claim cycle's own record (every state flip is a revision).

CREATE TABLE provider_claims (
    id                UUID PRIMARY KEY,
    provider_id       UUID NOT NULL REFERENCES provider_profiles (id),
    claimant_user_id  UUID NOT NULL,
    contact_channel   VARCHAR(200) NOT NULL,
    note              VARCHAR(2000),
    state             VARCHAR(20) NOT NULL,
    decided_by        VARCHAR(200),
    decision_note     VARCHAR(2000),
    decided_at        TIMESTAMPTZ,
    is_deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    version           BIGINT NOT NULL DEFAULT 0,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- The closed claim-state vocabulary (PENDING → APPROVED/REJECTED/
    -- WITHDRAWN; the decided states are terminal — a new claim is a NEW
    -- row, the rejected claimant's own recovery path).
    CONSTRAINT chk_provider_claims_state
        CHECK (state IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')) NOT VALID,
    -- The decision's shape: a PENDING claim carries no verdict, and
    -- every decided claim carries its who/when (APPROVED/REJECTED — the
    -- administrator; WITHDRAWN — the claimant's own hand).
    CONSTRAINT chk_provider_claims_decision_shape
        CHECK ((state = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
            OR (state <> 'PENDING' AND decided_by IS NOT NULL
                AND decided_at IS NOT NULL)) NOT VALID
);

ALTER TABLE provider_claims VALIDATE CONSTRAINT chk_provider_claims_state;
ALTER TABLE provider_claims VALIDATE CONSTRAINT chk_provider_claims_decision_shape;

-- ONE LIVE CLAIM PER PAGE (the register's «بناء الدورة بمالك واحد»):
-- the partial unique index over the PENDING live rows — the service's
-- pre-write guard answers the friendly 409, this index answers the
-- concurrent-race 409 (the L31/23505 law).
CREATE UNIQUE INDEX uq_provider_claims_one_live
    ON provider_claims (provider_id)
    WHERE state = 'PENDING' AND is_deleted = FALSE;

-- The administrative queue's own read: the review board filters by page
-- and/or state over the live rows.
CREATE INDEX idx_provider_claims_queue
    ON provider_claims (provider_id, state)
    WHERE is_deleted = FALSE;

CREATE TABLE provider_claims_aud (
    id                UUID NOT NULL,
    rev               INTEGER NOT NULL,
    revtype           SMALLINT,
    provider_id       UUID,
    claimant_user_id  UUID,
    contact_channel   VARCHAR(200),
    note              VARCHAR(2000),
    state             VARCHAR(20),
    decided_by        VARCHAR(200),
    decision_note     VARCHAR(2000),
    decided_at        TIMESTAMPTZ,
    is_deleted        BOOLEAN,
    version           BIGINT,
    created_by        VARCHAR(200),
    created_at        TIMESTAMPTZ,
    updated_by        VARCHAR(200),
    updated_at        TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- The ownership handover's own clock (J14): NULL while the page is the
-- platform's unowned pre-seed, set the moment an APPROVED claim links
-- the claimant — the measured proof the journey asserts alongside
-- provider_profiles.user_id. The Envers mirror gains the column in the
-- V56/V97/V218 style (nullable there — a DEL revision carries the id
-- alone).
ALTER TABLE provider_profiles
    ADD COLUMN claimed_at TIMESTAMPTZ;

ALTER TABLE provider_profiles_aud
    ADD COLUMN claimed_at TIMESTAMPTZ;

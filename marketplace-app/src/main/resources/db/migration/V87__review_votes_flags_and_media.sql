-- W1 (yelp-level plan §4.5 — the "أصوات/إشارات/وسائط المراجعة" migration):
-- (a) the VALIDATE steps of the V72/V73 widenings — each ALTER runs under its
--     OWN statement's SHARE UPDATE EXCLUSIVE alone (the V66/V69 measured
--     lesson: the DROP/ADD transactions committed already; sharing THEIR
--     transactions would have scanned under ACCESS EXCLUSIVE and blocked live
--     traffic). The CREATE TABLEs below take ACCESS EXCLUSIVE on tables no
--     session can see yet — zero conflict with the validations.
-- (b) review_votes — the "helpful" signal (G6), unique per (review, voter)
--     over LIVE rows (a soft-deleted unvote frees the pair — the re-vote).
-- (c) review_flags — the INTERNAL fraud signals of §4.5 (recorded, never
--     auto-blocked in the first release); the flag_type CHECK is the D-N7
--     membership guard (the FlagType enum's DB twin).
-- (d) review_media — the review's photos (G5): MediaAsset.listing_id is NOT
--     NULL by design and the organic review has no listing, so the review
--     carries its own table and path. Same two-phase lifecycle columns and
--     display-order index as V32; the Envers mirror rides the V24 convention.
-- Checksums registered in migration-checksums.properties in this same PR.

ALTER TABLE content_reports VALIDATE CONSTRAINT chk_content_reports_target_type;
ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_origin_kind;
ALTER TABLE reviews VALIDATE CONSTRAINT ck_review_origin_booking;
ALTER TABLE reviews VALIDATE CONSTRAINT chk_reviews_moderation_status;

CREATE TABLE review_votes (
    id         UUID PRIMARY KEY,
    review_id  UUID NOT NULL REFERENCES reviews(id),
    voter_id   UUID NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One live vote per (review, voter) — the service's explicit 409 first, this
-- partial unique index the concurrent-insert backstop; the soft delete frees
-- the pair for a legitimate re-vote.
CREATE UNIQUE INDEX uq_review_votes_review_voter
    ON review_votes (review_id, voter_id) WHERE is_deleted = FALSE;
CREATE INDEX idx_review_votes_review
    ON review_votes (review_id) WHERE is_deleted = FALSE;

CREATE TABLE review_votes_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    review_id  UUID,
    voter_id   UUID,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE review_flags (
    id         UUID PRIMARY KEY,
    review_id  UUID NOT NULL REFERENCES reviews(id),
    flag_type  VARCHAR(40) NOT NULL,
    details    VARCHAR(500),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_review_flags_type
        CHECK (flag_type IN ('BURST_ON_PROVIDER', 'NEW_ACCOUNT_ACTIVITY', 'TEXT_SIMILARITY'))
);

CREATE INDEX idx_review_flags_review
    ON review_flags (review_id) WHERE is_deleted = FALSE;

CREATE TABLE review_flags_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    review_id  UUID,
    flag_type  VARCHAR(40),
    details    VARCHAR(500),
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE review_media (
    id           UUID PRIMARY KEY,
    review_id    UUID NOT NULL REFERENCES reviews(id),
    uploader_id  UUID NOT NULL,
    object_key   VARCHAR(500) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes   BIGINT NOT NULL CHECK (size_bytes > 0),
    status       VARCHAR(20) NOT NULL CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED')),
    position     INT NOT NULL CHECK (position > 0),
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_review_media_object_key UNIQUE (object_key)
);

CREATE INDEX idx_review_media_review_position
    ON review_media (review_id, position) WHERE is_deleted = FALSE;

CREATE TABLE review_media_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    review_id    UUID,
    uploader_id  UUID,
    object_key   VARCHAR(500),
    content_type VARCHAR(100),
    size_bytes   BIGINT,
    status       VARCHAR(20),
    position     INT,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

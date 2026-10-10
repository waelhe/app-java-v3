-- B-16 (compliance plan C.8 — the M2 store wave, «جذر المتجر ٢/٢»):
-- the Q&A pair's two tables — «أسئلة/أجوبة المنتج». The seller summary
-- needs NO table: it is three closed projections over the M1 products
-- root (the V116 idx_products_provider partial index carries every one
-- of them — the M2 wave exercises the M1 index from the public side).
--
-- Attribution rules (the governing plan + the house shapes):
--   * Full BaseEntity column set from day one (the V25/V32 lesson) —
--     C.8's own reference list carries auditing.html: the question's
--     revision trail records the asker, the answer's trail records the
--     answering seller (two actors, two honest trails — the very reason
--     the answer is its own row and not answer columns on the question).
--   * Plain UUID columns with DB-level FKs and NO JPA relations — the
--     V116 in-module precedent (store_category_code) verbatim:
--     product_questions.product_id → products(id), and
--     product_answers.question_id → product_questions(id). The FKs are
--     enforceable from day one (both referenced tables landed in V116).
--   * One LIVE answer per question — the PARTIAL UNIQUE in the
--     V70/V157/V160 shape: uq_product_answers_question ON (question_id)
--     WHERE is_deleted = FALSE. A soft-deleted answer never blocks the
--     schema's own re-answer path; the service's existsByQuestionId gate
--     answers 409 BEFORE any write (defense in depth, the house order).
--   * The _aud mirrors in the V24 shape (all columns nullable) — the
--     V33 lesson: base-table columns without the _aud twin break audit
--     INSERTs silently.
--   * The CHECKs in the V64 shape: the body bound the house pins in
--     Java (2000) is the schema's own too — a blank body never lands.
--
-- Numbering: V170 — Track B's range (V150-V189). The V166-V169 band belongs to #529's community-post search wave (merged to main first).
--
-- Checksum registered in migration-checksums.properties in this same
-- unit (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- 1) The question half: the buyer's ask on a live product.
CREATE TABLE product_questions (
    id         UUID PRIMARY KEY,
    product_id UUID NOT NULL,
    asker_id   UUID NOT NULL,
    body       VARCHAR(2000) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_product_questions_product
        FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT chk_product_questions_body_shape
        CHECK (length(btrim(body)) >= 1)
);

-- The storefront's public Q&A feed: newest first on the complete sort
-- key (created_at DESC, id DESC — the community feed's own discipline:
-- pagination on the complete key, never a timestamp alone).
CREATE INDEX idx_product_questions_feed
    ON product_questions (product_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- 2) The answer half: the seller's single official answer.
CREATE TABLE product_answers (
    id          UUID PRIMARY KEY,
    question_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    body        VARCHAR(2000) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_product_answers_question
        FOREIGN KEY (question_id) REFERENCES product_questions (id),
    CONSTRAINT chk_product_answers_body_shape
        CHECK (length(btrim(body)) >= 1)
);

-- One LIVE answer per question (the V70/V157/V160 partial-unique
-- shape): the one-answer invariant held at the schema level.
CREATE UNIQUE INDEX uq_product_answers_question
    ON product_answers (question_id)
    WHERE is_deleted = FALSE;

-- The answer-assembly bulk read's one scan (the derived IN query).
CREATE INDEX idx_product_answers_question
    ON product_answers (question_id)
    WHERE is_deleted = FALSE;

-- 3) The Envers audit mirrors (the V24 convention; the V33 lesson).
CREATE TABLE product_questions_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    product_id UUID,
    asker_id   UUID,
    body       VARCHAR(2000),
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE product_answers_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    question_id UUID,
    provider_id UUID,
    body        VARCHAR(2000),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

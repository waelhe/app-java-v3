-- B-14 (compliance plan C.4 — the platform identity's «تعرف على» row:
-- a community-built integrated guide about the neighborhood and its
-- residents): the knowledge module's table. The search integration
-- rides EVENTS (the module-owned KnowledgeEntryPublishedEvent /
-- KnowledgeEntryWithdrawnEvent records on the exposed interface — the
-- eventual consumer is the search side, a reserve module under the
-- late-lander rule), and the table itself is search-READY from day one:
-- the GIN index over the coalesced title+body tsvector, the exact V9
-- pattern the listings search established ('simple' — the house's
-- measured tokenizer choice, the same one the module's
-- websearch_to_tsquery reads ride).
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * Full BaseEntity columns from day one (the V25/V32 lesson).
--   * CHECK in the V44 locking shape: NOT VALID + inline VALIDATE —
--     the table is brand-new and empty (the V52/V58 precedent).
--   * Plain UUID columns for the cross-module seams — author_id
--     (users.id space, the A1 convention) and location_id (the geo
--     tree node) carry NO relation across module boundaries (the
--     V32/V48/V54 discipline).
--   * The _aud mirror in the V24 shape: all columns nullable.
--
-- Numbering: V156 — Track B's range (V150-V189).

CREATE TABLE knowledge_entries (
    id          UUID PRIMARY KEY,
    author_id   UUID NOT NULL,
    location_id UUID NOT NULL,
    category    VARCHAR(20) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The guide's own vocabulary guard (the enum mirror — a future value
-- widens by migration, never by rebuilding the type).
ALTER TABLE knowledge_entries ADD CONSTRAINT chk_knowledge_entries_category
    CHECK (category IN ('PLACES','SERVICES','HISTORY','PEOPLE','TIPS')) NOT VALID;
ALTER TABLE knowledge_entries VALIDATE CONSTRAINT chk_knowledge_entries_category;

-- The neighborhood board's read: newest-contributed first with the
-- complete (created_at, id) key keeping the page boundary stable (D-N5).
CREATE INDEX idx_knowledge_entries_board
    ON knowledge_entries (location_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The neighborhood board's category axis.
CREATE INDEX idx_knowledge_entries_location_category
    ON knowledge_entries (location_id, category, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The contributor's own list.
CREATE INDEX idx_knowledge_entries_author
    ON knowledge_entries (author_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The full-text index (the V9 pattern verbatim): the GIN index over
-- the coalesced title+body tsvector with the SAME 'simple' tokenizer
-- the module's websearch_to_tsquery reads ride — the table is
-- search-ready from day one, whatever consumer eventually reads it.
CREATE INDEX idx_knowledge_entries_fts
    ON knowledge_entries USING gin (
        to_tsvector('simple', coalesce(title, '') || ' ' || coalesce(body, ''))
    );

-- Envers audit history (V24 convention): every contribution (ADD),
-- revision (MOD), and withdrawal (DEL) leaves a revision.
CREATE TABLE knowledge_entries_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    author_id   UUID,
    location_id UUID,
    category    VARCHAR(20),
    title       VARCHAR(200),
    body        TEXT,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

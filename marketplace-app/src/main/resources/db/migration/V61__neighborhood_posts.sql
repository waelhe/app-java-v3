-- L42 (neighborhood community plan §5 — the posts/feed/comments layer):
-- the neighborhood feed's own state. The community module's second
-- migration, on the V60 membership anchor — a post is the member's
-- authored text in exactly one neighborhood, a comment is authored text
-- on one post.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60 discipline): author_id lives in the users.id
-- space and arrives through the identity seams, location_id lives in the
-- geo_locations.id space and is resolved and LEVEL-CHECKED through the
-- shared GeoLookupPort before any write (the plan's D-N2 — the
-- neighborhood IS a level-3 node of the ONE administrative hierarchy).
-- post_id is the plan's sanctioned INTERNAL reference (the V7
-- messages.conversation_id precedent): a plain UUID column in Java, a
-- real FK in SQL — the post and its comments are one aggregate inside
-- the module's own boundary.
--
-- category and status are DB-enumerated columns (D-N7 — every enumerated
-- column carries its DB-level membership guard): GENERAL/CLASSIFIED/
-- LOST_FOUND today (RECOMMENDATION is L43's CHECK-widening point — the
-- plan's own sequence), VISIBLE/HIDDEN_BY_MODERATOR with the moderator
-- flip owned by L45 alone (the column exists from day one; no code path
-- in this layer writes anything but VISIBLE).
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so
-- the shared production database keeps serving traffic during the deploy.
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirrors follow the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- The feed index is the query's own shape: location-scoped, VISIBLE-only,
-- on the complete sort key (created_at DESC, id DESC — D-N5: no shaky
-- pages). The author indexes are NON-partial on purpose: the b-2 export
-- and the b-3 purge both scan by author THROUGH Hibernate's soft-delete
-- filter (native SQL), so a partial index would hide exactly the rows
-- those seams exist to see.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_posts (
    id          UUID PRIMARY KEY,
    author_id   UUID NOT NULL,
    location_id UUID NOT NULL,
    category    VARCHAR(20) NOT NULL,
    title       VARCHAR(200) NOT NULL,
    body        TEXT NOT NULL,
    status      VARCHAR(30) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_neighborhood_posts_category
        CHECK (category IN ('GENERAL', 'CLASSIFIED', 'LOST_FOUND')) NOT VALID,
    CONSTRAINT chk_neighborhood_posts_status
        CHECK (status IN ('VISIBLE', 'HIDDEN_BY_MODERATOR')) NOT VALID
);

ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_category;
ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_status;

-- The feed query's own index (D-N5): location-scoped, VISIBLE-only, on
-- the complete sort key — created_at DESC, id DESC — so a page boundary
-- is stable even when two posts land in the same second.
CREATE INDEX idx_neighborhood_posts_feed
    ON neighborhood_posts (location_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE AND status = 'VISIBLE';

-- The author seams (b-2 export / b-3 purge) ride NON-partial indexes:
-- native SQL scans past the soft-delete filter by design (b-5's
-- discrimination — a deleted post is stored personal data until the
-- retention window closes).
CREATE INDEX idx_neighborhood_posts_author
    ON neighborhood_posts (author_id, created_at, id);

-- The comments aggregate (the V7 messages precedent: plain UUID column
-- in Java, a real FK inside the module's own boundary).
CREATE TABLE post_comments (
    id         UUID PRIMARY KEY,
    post_id    UUID NOT NULL REFERENCES neighborhood_posts(id),
    author_id  UUID NOT NULL,
    body       TEXT NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The chronological comment read: post-scoped on its complete sort key.
CREATE INDEX idx_post_comments_post
    ON post_comments (post_id, created_at, id)
    WHERE is_deleted = FALSE;

-- The comment author seams (b-2 export / b-3 purge), NON-partial — same
-- reasoning as idx_neighborhood_posts_author.
CREATE INDEX idx_post_comments_author
    ON post_comments (author_id, created_at, id);

-- Envers audit history (V24 convention) — every post, comment, author
-- delete and future moderation flip is a revision; the moderation and
-- export surfaces read it.
CREATE TABLE neighborhood_posts_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    author_id   UUID,
    location_id UUID,
    category    VARCHAR(20),
    title       VARCHAR(200),
    body        TEXT,
    status      VARCHAR(30),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE post_comments_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    post_id    UUID,
    author_id  UUID,
    body       TEXT,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

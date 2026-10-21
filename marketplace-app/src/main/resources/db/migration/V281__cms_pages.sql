-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696 — the
-- D-CMS-001 ownership decision): the CMS PAGE store. The platform has no
-- dashboard-managed content-page owner today (the provider business page
-- is a provider profile surface, not CMS content) — the console module
-- (the platform's administration owner: feature flags, remote config,
-- planning, geo settings) owns the new registry.
--
-- Pages are attributed by their page type (V282) and carry per-language
-- translations (title/content/seo) with the primary-language row living in
-- the base table — Saleor's own model: the base row is the primary
-- translation and translation rows carry every other language.
--
-- content is the serialized rich-text JSON document (EditorJS-shaped) —
-- stored as-is (JSONB), rendered on the wire as a String (the no-new-scalar
-- house decision). slug is the public identifier (unique over live rows).
--
-- House shapes: the V96 attribution rules (full BaseEntity columns,
-- same-script CHECK validation, partial unique indexes over live rows,
-- V24-shaped _aud mirrors).

CREATE TABLE cms_pages (
    id               UUID PRIMARY KEY,
    page_type_id     UUID NOT NULL,
    slug             VARCHAR(255) NOT NULL,
    title            VARCHAR(255) NOT NULL,
    content          JSONB,
    is_published     BOOLEAN NOT NULL DEFAULT FALSE,
    published_at     TIMESTAMPTZ,
    seo_title        VARCHAR(255),
    seo_description  VARCHAR(512),
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_pages_slug_shape_chk
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT cms_pages_title_nonblank_chk
        CHECK (length(btrim(title)) > 0)
);

CREATE UNIQUE INDEX cms_pages_slug_live_uq ON cms_pages (slug) WHERE is_deleted = FALSE;
CREATE INDEX cms_pages_published_idx ON cms_pages (is_published) WHERE is_deleted = FALSE;

-- The page-type FK lands with V282 (the same migration creates the target
-- table); it is added there, not here, so each script stays self-contained.

CREATE TABLE cms_page_translations (
    id               UUID PRIMARY KEY,
    page_id          UUID NOT NULL REFERENCES cms_pages (id),
    language_code    VARCHAR(8) NOT NULL,
    slug             VARCHAR(255),
    title            VARCHAR(255),
    content          JSONB,
    seo_title        VARCHAR(255),
    seo_description  VARCHAR(512),
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_page_translations_language_shape_chk
        CHECK (language_code ~ '^[a-z]{2}(-[A-Za-z]{2,4})?$')
);

CREATE UNIQUE INDEX cms_page_translations_page_lang_live_uq
    ON cms_page_translations (page_id, language_code) WHERE is_deleted = FALSE;

CREATE TABLE cms_pages_aud (
    id               UUID NOT NULL,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT,
    page_type_id     UUID,
    slug             VARCHAR(255),
    title            VARCHAR(255),
    content          JSONB,
    is_published     BOOLEAN,
    published_at     TIMESTAMPTZ,
    seo_title        VARCHAR(255),
    seo_description  VARCHAR(512),
    is_deleted       BOOLEAN,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

CREATE TABLE cms_page_translations_aud (
    id               UUID NOT NULL,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT,
    page_id          UUID,
    language_code    VARCHAR(8),
    slug             VARCHAR(255),
    title            VARCHAR(255),
    content          JSONB,
    seo_title        VARCHAR(255),
    seo_description  VARCHAR(512),
    is_deleted       BOOLEAN,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

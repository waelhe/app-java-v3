-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
-- navigation store — menus (a named tree) and menu items (the tree's nodes)
-- with per-language name translations. An item links to exactly one target
-- among: an external URL, a catalog CATEGORY id, or a CMS PAGE id — the
-- oneof CHECK keeps the target set honest (at most one of the three).
--
-- The tree structure: every item belongs to ONE menu; the parent_id edge
-- forms the forest. Cycle prevention lives in the owning service (the
-- move operation walks the ancestor chain before re-parenting); the
-- depth/level rendering is computed at read time from the edges.
--
-- House shapes: the V96 attribution rules (full BaseEntity columns,
-- same-script CHECK validation, partial unique indexes, V24-shaped _aud).

CREATE TABLE cms_menus (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(255) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_menus_slug_shape_chk
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT cms_menus_name_nonblank_chk
        CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX cms_menus_slug_live_uq ON cms_menus (slug) WHERE is_deleted = FALSE;

CREATE TABLE cms_menu_items (
    id          UUID PRIMARY KEY,
    menu_id     UUID NOT NULL REFERENCES cms_menus (id),
    parent_id   UUID REFERENCES cms_menu_items (id),
    name        VARCHAR(255) NOT NULL,
    url         VARCHAR(2048),
    category_id UUID,
    page_id     UUID REFERENCES cms_pages (id),
    position    INTEGER NOT NULL DEFAULT 0,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_menu_items_target_oneof_chk
        CHECK ((url IS NOT NULL)::int + (category_id IS NOT NULL)::int + (page_id IS NOT NULL)::int <= 1),
    CONSTRAINT cms_menu_items_name_nonblank_chk
        CHECK (length(btrim(name)) > 0),
    CONSTRAINT cms_menu_items_position_nonneg_chk
        CHECK (position >= 0),
    CONSTRAINT cms_menu_items_parent_same_menu_chk
        CHECK (parent_id IS NULL OR parent_id <> id)
);

CREATE INDEX cms_menu_items_menu_order_idx
    ON cms_menu_items (menu_id, parent_id, position) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX cms_menu_items_menu_root_name_live_uq
    ON cms_menu_items (menu_id, name) WHERE is_deleted = FALSE AND parent_id IS NULL;

CREATE TABLE cms_menu_item_translations (
    id              UUID PRIMARY KEY,
    menu_item_id    UUID NOT NULL REFERENCES cms_menu_items (id),
    language_code   VARCHAR(8) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_menu_item_translations_language_shape_chk
        CHECK (language_code ~ '^[a-z]{2}(-[A-Za-z]{2,4})?$'),
    CONSTRAINT cms_menu_item_translations_name_nonblank_chk
        CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX cms_menu_item_translations_item_lang_live_uq
    ON cms_menu_item_translations (menu_item_id, language_code) WHERE is_deleted = FALSE;

CREATE TABLE cms_menus_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    name        VARCHAR(255),
    slug        VARCHAR(255),
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

CREATE TABLE cms_menu_items_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    menu_id     UUID,
    parent_id   UUID,
    name        VARCHAR(255),
    url         VARCHAR(2048),
    category_id UUID,
    page_id     UUID,
    position    INTEGER,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

CREATE TABLE cms_menu_item_translations_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    menu_item_id    UUID,
    language_code   VARCHAR(8),
    name            VARCHAR(255),
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

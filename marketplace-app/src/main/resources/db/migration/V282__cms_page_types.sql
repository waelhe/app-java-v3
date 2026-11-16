-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
-- CMS page-type registry + the assigned attribute set. Page types classify
-- pages (the dashboard's page-type workbench); the assigned attributes
-- reference the CATALOG module's attribute registry by UUID — existence
-- validated at the composition layer through the catalog module's declared
-- AttributeReadSpi, no cross-module FK (the Modulith boundary).
--
-- This script also lands the cms_pages -> cms_page_types FK deferred from
-- V281 (the target table exists as of this script).

CREATE TABLE cms_page_types (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(255) NOT NULL,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_page_types_slug_shape_chk
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT cms_page_types_name_nonblank_chk
        CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX cms_page_types_slug_live_uq ON cms_page_types (slug) WHERE is_deleted = FALSE;

ALTER TABLE cms_pages ADD CONSTRAINT fk_cms_pages_page_type
    FOREIGN KEY (page_type_id) REFERENCES cms_page_types (id);

CREATE TABLE cms_page_type_attributes (
    id            UUID PRIMARY KEY,
    page_type_id  UUID NOT NULL REFERENCES cms_page_types (id),
    attribute_id  UUID NOT NULL,
    position      INTEGER NOT NULL DEFAULT 0,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT cms_page_type_attributes_position_nonneg_chk
        CHECK (position >= 0)
);

CREATE UNIQUE INDEX cms_page_type_attributes_pair_live_uq
    ON cms_page_type_attributes (page_type_id, attribute_id) WHERE is_deleted = FALSE;
CREATE INDEX cms_page_type_attributes_order_idx
    ON cms_page_type_attributes (page_type_id, position) WHERE is_deleted = FALSE;

CREATE TABLE cms_page_types_aud (
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

CREATE TABLE cms_page_type_attributes_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    page_type_id  UUID,
    attribute_id  UUID,
    position      INTEGER,
    is_deleted    BOOLEAN,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

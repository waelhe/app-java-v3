-- L-slice (agent-2 packet, the canonical Saleor plan §5-P2 — the Saleor
-- collection/collections read roots and the collectionCreate/Update/Delete/
-- BulkDelete/AddProducts/RemoveProducts/ReorderProducts/Translate mutation
-- roots): the storefront collection registry and its product membership.
--
-- The registry follows the V70 categories precedent (reference data):
-- every BaseEntity column from day one (the V25/V32 lesson) + the Envers
-- mirror in the V24 convention (the V33 lesson: base-table columns without
-- the _aud twin break audit INSERTs silently). The slug is the stable
-- API-facing key (what create/update carry and what the public collection
-- lookup addresses); name_en/name_ar are the display names (the platform's
-- own two-language row law); is_published is the row's own publication
-- fact (the pinned Saleor input's own field — the include-unpublished
-- read surface is the ADMIN capability, the registry's own law).
--
-- Slug uniqueness is PARTIAL over live rows (the V47 slug precedent — a
-- slug is PRESENTATION identity, released for reuse once its row is
-- soft-deleted), unlike the categories code (referential identity, kept
-- reserved): no declared-debt FK references collections.slug, so release
-- on delete is the honest rule. The DB CHECK pins the slug shape
-- (lowercase latin/digits/dashes) so casing drift can never reach the
-- column.
--
-- The membership table follows the V237 product_type_attribute_assignments
-- precedent: one row per (collection, product) pair with its own display
-- position (the collectionReorderProducts root's own ordering fact), the
-- live-pair partial unique index as the duplicate-add backstop, and the
-- same Envers mirror discipline. The position CHECK pins the no-negative
-- law; the ordering index carries the scroll's own (position, id) key.

CREATE TABLE collections (
    id           UUID PRIMARY KEY,
    slug         VARCHAR(255) NOT NULL,
    name_en      VARCHAR(100),
    name_ar      VARCHAR(100),
    is_published BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_collections_slug_shape
        CHECK (slug = lower(slug) AND length(slug) BETWEEN 1 AND 255
               AND slug ~ '^[a-z0-9-]+$')
        NOT VALID,
    CONSTRAINT chk_collections_slug_published_shape
        CHECK (length(slug) >= 1)
);

CREATE UNIQUE INDEX uq_collections_slug_live
    ON collections (slug)
    WHERE is_deleted = FALSE;

ALTER TABLE collections VALIDATE CONSTRAINT chk_collections_slug_shape;

-- The Envers mirror (the V24 convention).
CREATE TABLE collections_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    slug         VARCHAR(255),
    name_en      VARCHAR(100),
    name_ar      VARCHAR(100),
    is_published BOOLEAN,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- The collection membership registry (the V237 assignment precedent).
CREATE TABLE collection_products (
    id            UUID PRIMARY KEY,
    collection_id UUID NOT NULL REFERENCES collections(id),
    product_id    UUID NOT NULL REFERENCES products(id),
    position      INTEGER NOT NULL DEFAULT 0,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_collection_products_position CHECK (position >= 0) NOT VALID
);

-- The duplicate-add backstop: one LIVE row per (collection, product) pair
-- (a soft-deleted membership releases the pair for a later re-add — the
-- same release-on-delete law the slug carries).
CREATE UNIQUE INDEX uq_collection_products_pair_live
    ON collection_products (collection_id, product_id)
    WHERE is_deleted = FALSE;

-- The scroll's own ordering key: (position, product_id) inside the
-- collection's live membership (the keyset scroll the products field's
-- forward-only pagination rides).
CREATE INDEX idx_collection_products_order_live
    ON collection_products (collection_id, position, product_id)
    WHERE is_deleted = FALSE;

ALTER TABLE collection_products VALIDATE CONSTRAINT chk_collection_products_position;

-- The membership's Envers mirror (the V24 convention).
CREATE TABLE collection_products_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    collection_id UUID,
    product_id    UUID,
    position      INTEGER,
    is_deleted    BOOLEAN,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

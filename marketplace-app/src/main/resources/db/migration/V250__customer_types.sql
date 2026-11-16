-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): the
-- customer-type registry — the Saleor 3.23 B2B customer segmentation with
-- ASSIGNED ATTRIBUTES (customerTypeCreate/Update/Delete + the attribute
-- assign/unassign/reorder roots + the customerType/customerTypes reads).
--
-- The type is identity-owned (it classifies the single identity store's
-- customer accounts); the assigned attribute IDs reference the CATALOG
-- module's attribute registry by UUID (no cross-module FK — the Modulith
-- boundary; existence is validated at the composition layer through the
-- catalog module's declared AttributeReadSpi before any write lands here).
--
-- The default-type invariant (exactly one default row over live data) is
-- owned by the service under a lock; the partial index below carries the
-- read path and doubles as the uniqueness guard the service's swap leans on.
--
-- House shapes: the V96 attribution rules (full BaseEntity columns,
-- same-script CHECK validation, partial unique indexes, V24-shaped _aud
-- mirrors with nullable columns).

CREATE TABLE customer_types (
    id          UUID PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    slug        VARCHAR(255) NOT NULL,
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_types_slug_shape_chk
        CHECK (slug ~ '^[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT customer_types_name_nonblank_chk
        CHECK (length(btrim(name)) > 0)
);

CREATE UNIQUE INDEX customer_types_slug_live_uq
    ON customer_types (slug) WHERE is_deleted = FALSE;
CREATE UNIQUE INDEX customer_types_one_default_live_uq
    ON customer_types (is_default) WHERE is_deleted = FALSE AND is_default = TRUE;

-- The assigned attribute set: one row per (type, attribute) over live data,
-- positioned by the position column the reorder operation rewrites.
CREATE TABLE customer_type_attributes (
    id            UUID PRIMARY KEY,
    customer_type_id UUID NOT NULL REFERENCES customer_types (id),
    attribute_id  UUID NOT NULL,
    position      INTEGER NOT NULL DEFAULT 0,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT customer_type_attributes_position_nonneg_chk
        CHECK (position >= 0)
);

CREATE UNIQUE INDEX customer_type_attributes_pair_live_uq
    ON customer_type_attributes (customer_type_id, attribute_id) WHERE is_deleted = FALSE;
CREATE INDEX customer_type_attributes_order_idx
    ON customer_type_attributes (customer_type_id, position) WHERE is_deleted = FALSE;

CREATE TABLE customer_types_aud (
    id          UUID NOT NULL,
    rev         INTEGER NOT NULL,
    revtype     SMALLINT,
    name        VARCHAR(255),
    slug        VARCHAR(255),
    is_default  BOOLEAN,
    is_deleted  BOOLEAN,
    version     BIGINT,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ,
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
) WITH (fillfactor = 100);

CREATE TABLE customer_type_attributes_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    customer_type_id UUID,
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

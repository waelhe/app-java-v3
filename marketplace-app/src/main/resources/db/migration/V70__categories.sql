-- S6 (comprehensive repair plan §10/2.3): the listing category registry.
--
-- The measured defect: provider_listings.category is free text (V2:
-- varchar(100), no CHECK, no registry) — the API contract documented
-- exactly one value ("stay", the CatalogController examples) while the
-- column accepted anything.
--
-- The registry follows the V47 geo_locations precedent (reference data):
-- every BaseEntity column from day one (the V25/V32 lesson) + the Envers
-- mirror in the V24 convention (the V33 lesson: base-table columns without
-- the _aud twin break audit INSERTs silently). The code is the stable
-- API-facing key (what create/update carry); name_en/name_ar are the
-- display names; position is the display order.
--
-- The starter vocabulary is the platform's CODE-DOCUMENTED value only
-- ("stay"). The business model document declares the listing-classification
-- vocabulary an open product gate ("تصنيف نصوص اللوحات" — to be settled
-- with the field survey), so the vocabulary is DATA (INSERTs — evolving
-- without migrations), never invented here.
--
-- The FK on provider_listings.category -> categories.code is the DECLARED
-- debt with its closing point (the PR body): it lands with the product
-- vocabulary decision, because an enforced FK with a one-value vocabulary
-- either breaks the category-diversity tests or pollutes the production
-- vocabulary with test artifacts — both rejected. The application write
-- gate (CatalogService.requireKnownCategory — 400 on unknown code) is the
-- immediate enforcement.

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    code       VARCHAR(50) NOT NULL,
    name_en    VARCHAR(100),
    name_ar    VARCHAR(100),
    position   INT NOT NULL DEFAULT 0,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version    BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_categories_position_nonnegative CHECK (position >= 0) NOT VALID,
    CONSTRAINT chk_categories_code_shape
        CHECK (code = lower(code) AND length(code) BETWEEN 1 AND 50
               AND code ~ '^[a-z0-9-]+$')
        NOT VALID
);

-- Code uniqueness is GLOBAL (CodeRabbit round 1, adopted): the code is the
-- registry's identity — the value the declared-debt FK
-- (provider_listings.category -> categories.code) will reference. Identity
-- values are never recycled: a soft-deleted row keeps its code reserved so a
-- later row cannot silently re-point legacy listings that still carry it.
-- The slug precedent does not apply here — a slug is presentation identity
-- (recycling is cosmetic); this code is referential identity. Aligned with
-- the soft-delete-aware application precheck (findByCode never resolves a
-- soft-deleted row, so a reserved code answers the same clean 400).
CREATE UNIQUE INDEX uq_categories_code
    ON categories (code);

ALTER TABLE categories VALIDATE CONSTRAINT chk_categories_position_nonnegative;
ALTER TABLE categories VALIDATE CONSTRAINT chk_categories_code_shape;

-- The display-order read (the public categories endpoint).
CREATE INDEX idx_categories_position ON categories (position) WHERE is_deleted = FALSE;

-- The starter vocabulary: the code-documented value only.
INSERT INTO categories (id, code, name_en, name_ar, position)
VALUES ('11111111-1111-4111-8111-111111111111', 'stay', 'Stay', 'إقامة', 0);

-- Envers audit history (V24 convention; the seed itself bypasses Envers by
-- nature — the documented geo D-E11 precedent: the mirror records the first
-- real admin write).
CREATE TABLE categories_aud (
    id         UUID NOT NULL,
    rev        INTEGER NOT NULL,
    revtype    SMALLINT,
    code       VARCHAR(50),
    name_en    VARCHAR(100),
    name_ar    VARCHAR(100),
    position   INT,
    is_deleted BOOLEAN,
    version    BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

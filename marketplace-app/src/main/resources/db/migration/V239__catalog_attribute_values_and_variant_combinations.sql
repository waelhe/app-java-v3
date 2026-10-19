-- C1: persist actual typed product/variant attribute values and protect
-- buyer-selectable variant combinations with a server-derived canonical key.
-- Legacy products and free-form variants retain NULL type/value/key paths.
-- #654 owns V235; V236-V238 establish ProductType, reusable assignments,
-- and the nullable products.product_type_id relation before this migration.
ALTER TABLE product_variants
    ADD COLUMN option_combination_key VARCHAR(64);

ALTER TABLE product_variants_aud
    ADD COLUMN option_combination_key VARCHAR(64);

CREATE UNIQUE INDEX uq_product_variants_product_option_combination_live
    ON product_variants (product_id, option_combination_key)
    WHERE option_combination_key IS NOT NULL AND is_deleted = FALSE;

CREATE TABLE product_attribute_values (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES products(id),
    assignment_id UUID NOT NULL REFERENCES product_type_attribute_assignments(id),
    text_value VARCHAR(4000),
    number_value NUMERIC(18, 6),
    boolean_value BOOLEAN,
    option_id UUID REFERENCES catalog_attribute_options(id),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_attribute_value_exactly_one
        CHECK (num_nonnulls(text_value, number_value, boolean_value, option_id) = 1) NOT VALID
);
ALTER TABLE product_attribute_values VALIDATE CONSTRAINT chk_product_attribute_value_exactly_one;
CREATE UNIQUE INDEX uq_product_attribute_value_live
    ON product_attribute_values (product_id, assignment_id) WHERE is_deleted = FALSE;

CREATE TABLE product_attribute_values_aud (
    id UUID NOT NULL, rev INTEGER NOT NULL, revtype SMALLINT,
    product_id UUID, assignment_id UUID, text_value VARCHAR(4000),
    number_value NUMERIC(18, 6), boolean_value BOOLEAN, option_id UUID,
    is_deleted BOOLEAN, version BIGINT, created_by VARCHAR(200), created_at TIMESTAMPTZ,
    updated_by VARCHAR(200), updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

CREATE TABLE product_variant_attribute_values (
    id UUID PRIMARY KEY,
    product_variant_id UUID NOT NULL REFERENCES product_variants(id),
    assignment_id UUID NOT NULL REFERENCES product_type_attribute_assignments(id),
    text_value VARCHAR(4000),
    number_value NUMERIC(18, 6),
    boolean_value BOOLEAN,
    option_id UUID REFERENCES catalog_attribute_options(id),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_product_variant_attribute_value_exactly_one
        CHECK (num_nonnulls(text_value, number_value, boolean_value, option_id) = 1) NOT VALID
);
ALTER TABLE product_variant_attribute_values VALIDATE CONSTRAINT chk_product_variant_attribute_value_exactly_one;
CREATE UNIQUE INDEX uq_product_variant_attribute_value_live
    ON product_variant_attribute_values (product_variant_id, assignment_id) WHERE is_deleted = FALSE;

CREATE TABLE product_variant_attribute_values_aud (
    id UUID NOT NULL, rev INTEGER NOT NULL, revtype SMALLINT,
    product_variant_id UUID, assignment_id UUID, text_value VARCHAR(4000),
    number_value NUMERIC(18, 6), boolean_value BOOLEAN, option_id UUID,
    is_deleted BOOLEAN, version BIGINT, created_by VARCHAR(200), created_at TIMESTAMPTZ,
    updated_by VARCHAR(200), updated_at TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

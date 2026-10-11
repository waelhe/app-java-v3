-- V174 — Stage 8 (plan D-09, ADR-0004): the lending workflow. The ADR
-- precedes this schema (the plan's own ordering): the item is the
-- storefront's Product (catalog owns it — no parallel item record), the
-- fee is minor-unit money, the period's exclusivity is the PostgreSQL
-- EXCLUDE constraint (the V41 precedent verbatim — the serialization
-- point for competing borrowers), and the fee's money rides the EXISTING
-- payment engine (the LOAN origin joins BOOKING/AD/ORDER — no escrow
-- vocabulary, no second engine).

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- 1. The lending OFFER — the owner's own terms on their product (the
--    plan's «إسقاط الإعلان/التوفر منفصل عن معاملة الإعارة المؤكدة»: the
--    offer is the lending module's own record referencing the catalog's
--    product; the confirmed loans live in their own table below). One
--    offer per product (the UNIQUE key); the daily fee is minor-unit
--    money. Soft-delete = the offer withdrawn.
CREATE TABLE lending_offers (
    id               UUID PRIMARY KEY,
    product_id       UUID NOT NULL UNIQUE,
    owner_id         UUID NOT NULL,
    daily_fee_minor  BIGINT NOT NULL CHECK (daily_fee_minor >= 0),
    currency         VARCHAR(3) NOT NULL,
    deposit_minor    BIGINT NOT NULL DEFAULT 0 CHECK (deposit_minor >= 0),
    is_deleted       BOOLEAN NOT NULL DEFAULT FALSE,
    version          BIGINT NOT NULL DEFAULT 0,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_lending_offers_owner ON lending_offers (owner_id);

CREATE TABLE lending_offers_aud (
    id               UUID NOT NULL,
    rev              INTEGER NOT NULL,
    revtype          SMALLINT,
    product_id       UUID,
    owner_id         UUID,
    daily_fee_minor  BIGINT,
    currency         VARCHAR(3),
    deposit_minor    BIGINT,
    is_deleted       BOOLEAN,
    version          BIGINT,
    created_by       VARCHAR(200),
    created_at       TIMESTAMPTZ,
    updated_by       VARCHAR(200),
    updated_at       TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- 2. The loans — the confirmed transactions (the workflow's own
--    aggregate). The ADR precedes this schema (the plan's own ordering):
--    the item is the storefront's Product (catalog owns it — no parallel
--    item record), the fee is minor-unit money, the period's exclusivity
--    is the PostgreSQL EXCLUDE constraint (the V41 precedent verbatim —
--    the serialization point for competing borrowers), and the fee's
--    money rides the EXISTING payment engine (the LOAN origin joins
--    BOOKING/AD/ORDER — no escrow vocabulary, no second engine).

CREATE TABLE loans (
    id                 UUID PRIMARY KEY,
    product_id         UUID NOT NULL,
    owner_id           UUID NOT NULL,
    borrower_id        UUID NOT NULL,
    status             VARCHAR(20) NOT NULL
        CHECK (status IN ('REQUESTED', 'APPROVED', 'ACTIVE', 'RETURN_REQUESTED',
                          'RETURNED', 'CLOSED', 'DECLINED', 'CANCELLED', 'DISPUTED')),
    start_at           TIMESTAMPTZ NOT NULL,
    end_at             TIMESTAMPTZ NOT NULL,
    fee_minor          BIGINT NOT NULL CHECK (fee_minor >= 0),
    currency           VARCHAR(3) NOT NULL,
    payment_intent_id  UUID,
    paid_at            TIMESTAMPTZ,
    handover_at        TIMESTAMPTZ,
    returned_at        TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    cancel_reason      VARCHAR(500),
    -- The house BaseEntity columns (the V25 lesson) + the Envers mirror.
    is_deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    version            BIGINT NOT NULL DEFAULT 0,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT loans_period_sane_chk CHECK (start_at < end_at)
);

-- The period's exclusivity among the LIVE holds — the official PostgreSQL
-- EXCLUDE constraint with the status predicate (a CLOSED/CANCELLED loan
-- releases its period for rebooking). Two competing borrowers cannot hold
-- the same item for overlapping periods: the second INSERT answers the
-- constraint violation the service translates into the house 409 (the
-- service's own lock + overlap query is the friendly face; this is the
-- concurrency backstop — the V41 precedent verbatim).
ALTER TABLE loans ADD CONSTRAINT loans_live_period_exclusive
    EXCLUDE USING gist (
        product_id WITH =,
        tstzrange(start_at, end_at, '[)') WITH &&
    ) WHERE (status IN ('APPROVED', 'ACTIVE', 'RETURN_REQUESTED'));

CREATE INDEX idx_loans_owner ON loans (owner_id, status);
CREATE INDEX idx_loans_borrower ON loans (borrower_id, status);

-- The Envers audit mirror (the V24 convention verbatim).
CREATE TABLE loans_aud (
    id                 UUID NOT NULL,
    rev                INTEGER NOT NULL,
    revtype            SMALLINT,
    product_id         UUID,
    owner_id           UUID,
    borrower_id        UUID,
    status             VARCHAR(20),
    start_at           TIMESTAMPTZ,
    end_at             TIMESTAMPTZ,
    fee_minor          BIGINT,
    currency           VARCHAR(3),
    payment_intent_id  UUID,
    paid_at            TIMESTAMPTZ,
    handover_at        TIMESTAMPTZ,
    returned_at        TIMESTAMPTZ,
    closed_at          TIMESTAMPTZ,
    cancelled_at       TIMESTAMPTZ,
    cancel_reason      VARCHAR(500),
    is_deleted         BOOLEAN,
    version            BIGINT,
    created_by         VARCHAR(200),
    created_at         TIMESTAMPTZ,
    updated_by         VARCHAR(200),
    updated_at         TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- 2. The EXISTING payment engine learns the LOAN subject — the same
--    additive widening the ORDER origin received (the origin CHECK and
--    its pairing invariant rewritten in place, NOT VALID; the new rows
--    are the only LOAN rows).
ALTER TABLE payment_intents
    ADD COLUMN loan_id UUID;
ALTER TABLE payment_intents
    ADD CONSTRAINT payment_intents_loan_id_unique UNIQUE (loan_id);
ALTER TABLE payment_intents DROP CONSTRAINT chk_payment_intents_origin;
ALTER TABLE payment_intents
    ADD CONSTRAINT chk_payment_intents_origin
        CHECK (origin IN ('BOOKING', 'AD', 'ORDER', 'LOAN')) NOT VALID;
ALTER TABLE payment_intents DROP CONSTRAINT ck_payment_intents_origin_pairing;
ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_origin_pairing
        CHECK ((origin = 'BOOKING' AND booking_id IS NOT NULL AND ad_campaign_id IS NULL AND order_id IS NULL AND loan_id IS NULL)
            OR (origin = 'AD'      AND booking_id IS NULL     AND ad_campaign_id IS NOT NULL AND order_id IS NULL AND loan_id IS NULL)
            OR (origin = 'ORDER'   AND booking_id IS NULL     AND ad_campaign_id IS NULL AND order_id IS NOT NULL AND loan_id IS NULL)
            OR (origin = 'LOAN'    AND booking_id IS NULL     AND ad_campaign_id IS NULL AND order_id IS NULL AND loan_id IS NOT NULL)) NOT VALID;
ALTER TABLE payment_intents_aud
    ADD COLUMN loan_id UUID;

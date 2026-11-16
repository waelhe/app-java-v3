-- V217: road-routing estimates, courier opt-in/availability, automatic assignment and payable identity.
-- V215/V216 are intentionally left immutable; all follow-up schema evolution is additive.
ALTER TABLE delivery_quotes
    ADD COLUMN route_duration_seconds BIGINT NOT NULL DEFAULT 0
        CONSTRAINT delivery_quotes_route_duration_chk CHECK (route_duration_seconds >= 0),
    ADD COLUMN courier_compensation_minor BIGINT NOT NULL DEFAULT 0
        CONSTRAINT delivery_quotes_courier_compensation_chk CHECK (courier_compensation_minor >= 0);

ALTER TABLE delivery_quotes_aud
    ADD COLUMN route_duration_seconds BIGINT,
    ADD COLUMN courier_compensation_minor BIGINT;

CREATE TABLE delivery_couriers (
    user_id UUID PRIMARY KEY REFERENCES users(id),
    available BOOLEAN NOT NULL DEFAULT FALSE,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    location_updated_at TIMESTAMPTZ,
    current_delivery_request_id UUID UNIQUE,
    last_assigned_at TIMESTAMPTZ,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT delivery_couriers_coordinates_chk CHECK (
        (latitude IS NULL OR latitude BETWEEN -90 AND 90)
        AND (longitude IS NULL OR longitude BETWEEN -180 AND 180)
    ),
    CONSTRAINT delivery_couriers_location_pair_chk CHECK (
        (latitude IS NULL AND longitude IS NULL)
        OR (latitude IS NOT NULL AND longitude IS NOT NULL)
    ),
    CONSTRAINT delivery_couriers_available_location_chk CHECK (
        NOT available OR (latitude IS NOT NULL AND longitude IS NOT NULL AND location_updated_at IS NOT NULL)
    )
);

CREATE INDEX idx_delivery_couriers_dispatch
    ON delivery_couriers(available, last_assigned_at, location_updated_at)
    WHERE is_deleted = FALSE;

ALTER TABLE delivery_couriers
    ADD CONSTRAINT delivery_couriers_current_request_fk
        FOREIGN KEY (current_delivery_request_id) REFERENCES delivery_requests(id);

CREATE TABLE delivery_couriers_aud (
    user_id UUID NOT NULL,
    rev INTEGER NOT NULL,
    revtype SMALLINT,
    available BOOLEAN,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    location_updated_at TIMESTAMPTZ,
    current_delivery_request_id UUID,
    last_assigned_at TIMESTAMPTZ,
    version BIGINT,
    created_by VARCHAR(200),
    created_at TIMESTAMPTZ,
    updated_by VARCHAR(200),
    updated_at TIMESTAMPTZ,
    PRIMARY KEY(user_id, rev)
);

ALTER TABLE delivery_requests
    ADD COLUMN route_duration_seconds BIGINT NOT NULL DEFAULT 0
        CONSTRAINT delivery_requests_route_duration_chk CHECK (route_duration_seconds >= 0),
    ADD COLUMN courier_compensation_minor BIGINT NOT NULL DEFAULT 0
        CONSTRAINT delivery_requests_courier_compensation_chk CHECK (courier_compensation_minor >= 0),
    ADD COLUMN courier_user_id UUID;

ALTER TABLE delivery_requests
    ADD CONSTRAINT delivery_requests_courier_fk
        FOREIGN KEY (courier_user_id) REFERENCES delivery_couriers(user_id);

ALTER TABLE delivery_requests
    DROP COLUMN courier_name,
    DROP COLUMN courier_phone;

CREATE INDEX idx_delivery_requests_courier_status
    ON delivery_requests(courier_user_id, status, created_at DESC)
    WHERE courier_user_id IS NOT NULL;

ALTER TABLE delivery_requests_aud
    ADD COLUMN route_duration_seconds BIGINT,
    ADD COLUMN courier_compensation_minor BIGINT,
    ADD COLUMN courier_user_id UUID;

ALTER TABLE delivery_requests_aud
    DROP COLUMN courier_name,
    DROP COLUMN courier_phone;

ALTER TABLE ledger_journal_postings
    ADD COLUMN recipient_id UUID;

ALTER TABLE ledger_journal_postings
    DROP CONSTRAINT ledger_journal_postings_debit_account_check,
    DROP CONSTRAINT ledger_journal_postings_credit_account_check;

ALTER TABLE ledger_journal_postings
    ADD CONSTRAINT ledger_journal_postings_debit_account_check
        CHECK (debit_account IN ('PSP_CLEARING', 'DELIVERY_CUSTOMER_ADVANCES', 'DELIVERY_REVENUE', 'DELIVERY_REFUNDS', 'DELIVERY_COURIER_PAYABLE')),
    ADD CONSTRAINT ledger_journal_postings_credit_account_check
        CHECK (credit_account IN ('PSP_CLEARING', 'DELIVERY_CUSTOMER_ADVANCES', 'DELIVERY_REVENUE', 'DELIVERY_REFUNDS', 'DELIVERY_COURIER_PAYABLE'));

ALTER TABLE ledger_journal_postings_aud
    ADD COLUMN recipient_id UUID;

CREATE INDEX idx_delivery_journal_recipient_created
    ON ledger_journal_postings(recipient_id, created_at DESC)
    WHERE recipient_id IS NOT NULL;

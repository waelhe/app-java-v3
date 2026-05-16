CREATE TABLE IF NOT EXISTS availability_slots (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    booked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT,
    CONSTRAINT chk_availability_slots_time CHECK (starts_at < ends_at)
);

CREATE INDEX idx_availability_slots_provider_time ON availability_slots(provider_id, starts_at, ends_at);
CREATE INDEX idx_availability_slots_provider_booked ON availability_slots(provider_id, booked);

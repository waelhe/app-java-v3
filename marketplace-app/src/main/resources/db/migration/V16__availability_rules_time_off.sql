CREATE TABLE IF NOT EXISTS provider_availability_rules (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    day_of_week VARCHAR(20) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT,
    CONSTRAINT chk_provider_availability_rule_time CHECK (start_time < end_time)
);

CREATE TABLE IF NOT EXISTS provider_time_off (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT,
    CONSTRAINT chk_provider_time_off_time CHECK (starts_at < ends_at)
);

CREATE INDEX idx_provider_availability_rules_provider_day ON provider_availability_rules(provider_id, day_of_week);
CREATE INDEX idx_provider_time_off_provider_time ON provider_time_off(provider_id, starts_at, ends_at);

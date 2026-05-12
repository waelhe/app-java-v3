CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    provider_id UUID NOT NULL,
    source_id UUID NOT NULL UNIQUE,
    entry_type VARCHAR(30) NOT NULL,
    amount_cents BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT
);

CREATE TABLE provider_balances (
    provider_id UUID PRIMARY KEY,
    available_cents BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT
);

CREATE INDEX idx_ledger_entries_provider ON ledger_entries(provider_id);

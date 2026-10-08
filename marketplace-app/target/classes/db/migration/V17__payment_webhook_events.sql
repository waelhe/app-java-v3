CREATE TABLE payment_webhook_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(50) NOT NULL,
    event_id VARCHAR(200) NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT
);

CREATE INDEX idx_payment_webhook_events_provider ON payment_webhook_events(provider);

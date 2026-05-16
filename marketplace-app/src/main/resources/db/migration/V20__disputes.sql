CREATE TABLE IF NOT EXISTS disputes (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL,
    opened_by UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT
);

CREATE INDEX idx_disputes_booking ON disputes(booking_id);
CREATE INDEX idx_disputes_status ON disputes(status);

CREATE TABLE provider_profiles (
    id UUID PRIMARY KEY,
    display_name VARCHAR(200) NOT NULL,
    bio VARCHAR(1000),
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT
);

CREATE INDEX idx_provider_profiles_status ON provider_profiles(status);

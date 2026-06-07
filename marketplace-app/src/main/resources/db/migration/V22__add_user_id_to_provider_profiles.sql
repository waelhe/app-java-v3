ALTER TABLE provider_profiles ADD COLUMN user_id UUID;

CREATE INDEX idx_provider_profiles_user_id ON provider_profiles(user_id);

-- Partial unique index for staging destinations. Concurrent creation
-- preserves ordinary INSERT/UPDATE/DELETE availability on populated tables.
-- Recovery follows V168: drop an INVALID leftover, run Flyway repair, retry.
CREATE UNIQUE INDEX CONCURRENTLY uq_media_assets_upload_object_key
    ON media_assets (upload_object_key)
    WHERE upload_object_key IS NOT NULL;

-- Review-media twin of V307; built independently so a failed concurrent
-- build cannot leave a second index from this migration half-completed.
-- Recovery follows V168: drop an INVALID leftover, run Flyway repair, retry.
CREATE UNIQUE INDEX CONCURRENTLY uq_review_media_upload_object_key
    ON review_media (upload_object_key)
    WHERE upload_object_key IS NOT NULL;

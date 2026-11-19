-- G3 upload-race closure: the client PUT is isolated in a short-lived
-- staging namespace. The published object_key remains immutable and is only
-- populated by the server-side CopyObject after HEAD verification.
--
-- Existing PENDING_UPLOAD rows retain their already-issued destination in
-- upload_object_key. The service treats equal upload/object keys as legacy
-- direct uploads; new requests always allocate media-upload-staging/<uuid>.
-- Envers mirrors receive the same audited column (V33/V76/V116 discipline).

ALTER TABLE media_assets
    ADD COLUMN upload_object_key VARCHAR(500);

ALTER TABLE media_assets_aud
    ADD COLUMN upload_object_key VARCHAR(500);

UPDATE media_assets
   SET upload_object_key = object_key
 WHERE status = 'PENDING_UPLOAD';

CREATE UNIQUE INDEX uq_media_assets_upload_object_key
    ON media_assets (upload_object_key)
    WHERE upload_object_key IS NOT NULL;

ALTER TABLE review_media
    ADD COLUMN upload_object_key VARCHAR(500);

ALTER TABLE review_media_aud
    ADD COLUMN upload_object_key VARCHAR(500);

UPDATE review_media
   SET upload_object_key = object_key
 WHERE status = 'PENDING_UPLOAD';

CREATE UNIQUE INDEX uq_review_media_upload_object_key
    ON review_media (upload_object_key)
    WHERE upload_object_key IS NOT NULL;

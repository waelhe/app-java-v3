-- Preserve the destination of uploads that were already pending when
-- the staging-key columns were introduced. New requests write distinct
-- media-upload-staging/<uuid> keys after this backfill has committed.

UPDATE media_assets
   SET upload_object_key = object_key
 WHERE status = 'PENDING_UPLOAD'
   AND upload_object_key IS NULL;

UPDATE review_media
   SET upload_object_key = object_key
 WHERE status = 'PENDING_UPLOAD'
   AND upload_object_key IS NULL;

-- Persist the destination of every legacy direct PUT URL. Before the
-- staging-key rollout, requestUpload signed the permanent object_key for
-- both PENDING_UPLOAD and already-UPLOADED rows. A still-valid URL can be
-- reused until expiry, so keep the exact legacy destination available to
-- account erasure for all rows that have not reached the terminal PURGED
-- state. This is cleanup evidence, not revocation of the signed URL.

UPDATE media_assets
   SET upload_object_key = object_key
 WHERE status <> 'PURGED'
   AND upload_object_key IS NULL;

UPDATE review_media
   SET upload_object_key = object_key
 WHERE status <> 'PURGED'
   AND upload_object_key IS NULL;

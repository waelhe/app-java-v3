-- G3 upload-race closure: add the nullable staging-key columns only.
-- Keep this transactional migration short; data backfill and index builds
-- are isolated into later migrations so populated tables are not held under
-- an ACCESS EXCLUSIVE lock while indexes are constructed.

ALTER TABLE media_assets
    ADD COLUMN upload_object_key VARCHAR(500);

ALTER TABLE media_assets_aud
    ADD COLUMN upload_object_key VARCHAR(500);

ALTER TABLE review_media
    ADD COLUMN upload_object_key VARCHAR(500);

ALTER TABLE review_media_aud
    ADD COLUMN upload_object_key VARCHAR(500);

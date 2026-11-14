-- G3 (owner-gate batch 6a — the media purge's tombstone): the VALIDATE
-- half of the V300 pair. Every CHECK lands NOT VALID (the V44 shape);
-- each VALIDATE below runs under its OWN statement's SHARE UPDATE
-- EXCLUSIVE alone — the V68/V69 + V87 measured lesson.
--
-- Validates clean by construction: the live media_assets/review_media
-- rows carry only the two pre-G3 members — PURGED only enters with this
-- wave's purge adapter.

ALTER TABLE media_assets VALIDATE CONSTRAINT media_assets_status_check;
ALTER TABLE review_media VALIDATE CONSTRAINT review_media_status_check;

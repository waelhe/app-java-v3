-- L28 (feature-expansion roadmap §5, Week 4): image thumbnail processing.
-- One nullable column on media_assets — thumb_object_key:
--   NULL                      = processing pending (or failed, awaiting the
--                               documented event-publication resubmission);
--   '{objectKey}/thumb'       = a real scaled thumbnail object exists;
--   = object_key              = non-processable MIME (webp/gif), an
--                               already-small original, or a source over the
--                               raster budget (header-declared pixels above
--                               marketplace.media.limits.thumb-source-max-pixels
--                               — never decoded) — thumbnail IS the original
--                               by design (no duplicate stored).
-- Deterministic key pattern {objectKey}/thumb: no UNIQUE constraint is
-- needed — the key is derived from the already-unique parent key
-- (uq_media_assets_object_key, V32) and the writer is write-once per asset
-- (MediaAsset.recordThumbKey idempotence).
-- Envers audit mirror gains the same column (V24 convention) so @Audited
-- snapshots keep writing — the V37 pattern for column additions.

ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS thumb_object_key VARCHAR(500);

ALTER TABLE media_assets_aud
    ADD COLUMN IF NOT EXISTS thumb_object_key VARCHAR(500);

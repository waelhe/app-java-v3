-- L48 (the Nextdoor-2026 completeness wave — gap #2, post images): the
-- media line's target generalization, the decision the community plan's
-- D-C3 registered and pointed here ("PR يعمّم الهدف"): one media pipeline
-- (presigned upload + HeadObject verification + deterministic thumbnail,
-- L28) attached to a DOMAIN TARGET — until today exactly one target
-- existed (the listing); this migration widens the vocabulary to a second
-- target (the neighborhood post) WITHOUT touching the listing path's
-- shape: listing_id/provider_id/object_key/status/position keep their
-- V32/V43 contracts byte-identical, and every existing row defaults to
-- the listing kind it has always been.
--
-- The generalization is an explicit discriminator, never a nullable
-- guess: owner_kind pins each row's target vocabulary (CHECK, the V61
-- D-N7 membership-guard pattern), and the exactly-one-target invariant
-- is a table-level CHECK — a LISTING row must carry listing_id and no
-- post_id, a POST row the mirror. Post assets keep provider_id as the
-- uploading user's id (the measured A1 fact — media_assets.provider_id
-- IS a user id, the export/purge ownership basis), which for posts is
-- the post author (NeighborhoodPost.authorId, resolved through the new
-- PostLookupPort seam — never a JPA relation across module boundaries,
-- the V32/V48/V52/V54/V60/V61/V64 discipline verbatim).
--
-- post_id is the plan's sanctioned INTERNAL-reference shape in reverse:
-- the community module owns the aggregate (V61), and the media row
-- references it from OUTSIDE the boundary as a plain UUID column with NO
-- FK constraint (the exact V60 membership -> users.id shape; unlike
-- post_reactions.V73 there is no shared aggregate here to justify an
-- FK across two Modulith boundaries). Existence and authorship are
-- resolved through the port seam at write time; a deleted post leaves
-- its media rows behind by the same documented stance as a deleted
-- listing's (bucket lifecycle rules own orphans — MediaService.delete
-- javadoc).
--
-- The feed's media read is one IN query over the page's post ids
-- (findByPostIdInAndStatus... ORDER BY post_id, position) — the
-- post_id-leading partial index below serves exactly that scan, the
-- V73 uq_post_reactions one-index-two-reads reasoning: UPLOADED-only
-- rows count (a PENDING_UPLOAD row is a presigned URL that was issued
-- but never storage-verified — the MediaLookupPort contract), and
-- soft-deleted rows stay invisible to the derived query.
--
-- Envers audit mirror gains the same columns (V24 convention; the V33
-- lesson: base-table columns without the _aud twin break audit INSERTs
-- silently, and the V43 precedent for column additions on this very
-- table). owner_kind rides the mirror as nullable-without-default — Envers
-- writes the entity's value explicitly, and the aud table never applied
-- the NOT NULL discipline to begin with (V32's aud shape).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- The discriminator vocabulary (the V61 CHECK membership-guard pattern).
ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS owner_kind VARCHAR(20) NOT NULL DEFAULT 'LISTING';

-- The post target — NULL for every listing row, past and future.
ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS post_id UUID;

-- Backfill is a no-op: every pre-L48 row is a listing row and the
-- DEFAULT above already states it. The CHECK still pins the invariant
-- for every future write.
ALTER TABLE media_assets
    ADD CONSTRAINT ck_media_assets_owner_kind
        CHECK (owner_kind IN ('LISTING', 'POST'));

ALTER TABLE media_assets
    ADD CONSTRAINT ck_media_assets_one_target
        CHECK (
            (owner_kind = 'LISTING' AND listing_id IS NOT NULL AND post_id IS NULL)
            OR
            (owner_kind = 'POST' AND post_id IS NOT NULL AND listing_id IS NULL)
        );

-- The feed read's one scan: UPLOADED-only, soft-delete-aware (the
-- derived queries' @SoftDelete exclusion needs the partial predicate to
-- stay selective — the V73 index-shape reasoning verbatim).
CREATE INDEX idx_media_assets_post_feed
    ON media_assets (post_id, position)
    WHERE post_id IS NOT NULL AND is_deleted = FALSE AND status = 'UPLOADED';

-- Envers audit mirror (V24 convention, V43 precedent on this table).
ALTER TABLE media_assets_aud
    ADD COLUMN IF NOT EXISTS owner_kind VARCHAR(20);

ALTER TABLE media_assets_aud
    ADD COLUMN IF NOT EXISTS post_id UUID;

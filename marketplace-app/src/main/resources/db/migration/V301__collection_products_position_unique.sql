-- B6 (CAT-01 — the 2026-11-03 adversarial review, review-pr-808): the
-- collection membership's position column had NO uniqueness guard and NO
-- serialization discipline: two concurrent collectionAddProducts calls
-- both read the same live MAX(position) and mint the SAME next position
-- (the silent duplicate the wave-19 review closed for the category
-- registry and the type-attribute assignments), and a concurrent
-- reorder against an append interleaves the same way. The display order
-- turns unstable with no error anywhere on the wire.
--
-- The corrective shape (the media registry's own discipline, A-17/C.7,
-- plus the assignment registry's V237 position index):
-- (1) a deterministic backfill that renumbers the LIVE membership of
--     every collection whose live positions carry a duplicate —
--     (position, id) order, contiguous 1..n — so the index below can be
--     created on already-accumulated data;
-- (2) the partial unique index uq_collection_products_position_live —
--     one live position per collection (a soft-deleted membership
--     releases its slot, the same release-on-delete law the pair index
--     already carries);
-- (3) the advisory transaction lock
--     (CollectionProductRepository.lockCollectionPositionAllocation —
--     the media registry's own pattern) serializes the position
--     allocation itself in the write service, so the index stays a
--     backstop, never the user-visible answer.
--
-- Never modify existing migrations (AGENTS.md / MigrationChecksumGuardTest
-- — the 2026-09-14 incident class): this is a NEW versioned migration.
-- Checksum registered in migration-checksums.properties in this same
-- commit.

-- (1) The duplicate backfill — surgical: only collections whose LIVE
--     membership actually carries a duplicate position are renumbered
--     (untouched collections keep their rows byte-identical; migration
--     churn stays invisible where nothing is broken). The renumber is
--     deterministic: (position, id) order, contiguous 1..n.
WITH duplicated_collections AS (
    SELECT collection_id
    FROM collection_products
    WHERE is_deleted = FALSE
    GROUP BY collection_id
    HAVING COUNT(DISTINCT position) < COUNT(*)
),
renumbered AS (
    SELECT cp.id,
           ROW_NUMBER() OVER (PARTITION BY cp.collection_id
                              ORDER BY cp.position, cp.id) AS new_position
    FROM collection_products cp
    JOIN duplicated_collections dc ON dc.collection_id = cp.collection_id
    WHERE cp.is_deleted = FALSE
)
UPDATE collection_products cp
SET position = r.new_position
FROM renumbered r
WHERE cp.id = r.id
  AND cp.position <> r.new_position;

-- (2) The live-position uniqueness backstop (the V237 assignment
--     registry's own position index shape).
CREATE UNIQUE INDEX uq_collection_products_position_live
    ON collection_products (collection_id, position)
    WHERE is_deleted = FALSE;

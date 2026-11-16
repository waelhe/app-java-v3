-- L48 fix round (the production measurement 2026-10-01, deployment 2d25ed95):
-- V76 generalized the media target but left V32's listing_id NOT NULL in
-- place — the two constraints contradict for every POST row (the one-target
-- CHECK demands listing_id IS NULL; V32's NOT NULL demands the opposite),
-- so a post photo's INSERT died at the database with
-- 'null value in column "listing_id" violates not-null constraint' while
-- every test stayed green: the test profile builds its schema from the
-- ENTITY mappings (ddl-auto), and JPA nullability never carried V32's
-- column constraint — the documented 'test schema != production schema'
-- class (SYSTEM.md §7, the V30 lesson family).
--
-- The fix is the minimal honest generalization V76 intended but did not
-- write: listing_id becomes nullable, exactly like post_id — the
-- exactly-one-target CHECK (ck_media_assets_one_target) remains the sole
-- authority for which target a row carries. No index, default, or column
-- shape changes: idx_media_assets_listing_position is partial
-- (WHERE is_deleted = FALSE) and Postgres treats NULL keys in it as
-- simply not-indexed rows, which is correct — a POST row is never read
-- through the listing scan. Existing LISTING rows are untouched (they
-- all carry listing_id).
--
-- The regression guard for this exact class rides the same PR: a
-- Flyway-booting integration test (ddl-auto=none) that drives the REAL
-- requestPostUpload service path against the REAL migrated schema.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE media_assets
    ALTER COLUMN listing_id DROP NOT NULL;

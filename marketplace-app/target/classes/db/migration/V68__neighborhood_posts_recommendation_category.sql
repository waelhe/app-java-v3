-- L43 (neighborhood community plan §5 — the recommendations category):
-- the category CHECK's widening. PostCategory gains RECOMMENDATION —
-- the plan's own sequence, reserved by V61's comment ("RECOMMENDATION is
-- L43's CHECK-widening point") and by the enum's javadoc since L42 — so
-- this migration plus the enum's one constant IS the whole layer (D-N10:
-- no new business entity; the feed, the filters, the comments and the
-- rate limits all ride the category axis L42 already built).
--
-- The semantics the enum's javadoc carries (the plan's own words): a
-- RECOMMENDATION post is a neighbor asking for a local-service
-- recommendation («أبحث عن سبّاك موثوق») or offering one — authored text
-- in the same feed, behind the same membership gate, on the same one
-- filter axis. The organized business directory and its structured
-- fields stay OUT (G-N4 — a product gate behind the plan's §7, not a
-- layer).
--
-- Widening an EXISTING constraint (V61's chk_neighborhood_posts_category)
-- in the V65 shape — the house's measured precedent for exactly this
-- situation (L45 widened notification_preferences' type CHECK the same
-- way): DROP the old + ADD the widened list NOT VALID. NOT VALID is the
-- V44 locking shape: a metadata-only statement (enforced for every NEW
-- row immediately), so live feed writes never wait on a scan.
--
-- The VALIDATE step rides its OWN migration (V69) — the V66 lesson
-- verbatim: Flyway runs each versioned migration in its own transaction,
-- and a transaction RETAINS every lock it acquired until it commits, so a
-- VALIDATE sharing this transaction would scan under the DROP/ADD's
-- still-held ACCESS EXCLUSIVE, blocking ordinary reads and writes for
-- the scan's duration. Alone in V69 the VALIDATE statement's own lock is
-- SHARE UPDATE EXCLUSIVE — the shared production database keeps serving
-- traffic during the deploy.
--
-- No column change, no _aud change (the mirror carries category as
-- VARCHAR(20) — the widened vocabulary fits the existing width), no new
-- index (the feed index is category-agnostic: location-scoped,
-- VISIBLE-only, on the complete sort key — D-N5).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE neighborhood_posts DROP CONSTRAINT IF EXISTS chk_neighborhood_posts_category;

ALTER TABLE neighborhood_posts
    ADD CONSTRAINT chk_neighborhood_posts_category
    CHECK (category IN ('GENERAL', 'CLASSIFIED', 'LOST_FOUND', 'RECOMMENDATION')) NOT VALID;

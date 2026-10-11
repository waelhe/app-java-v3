-- JT-20 (#536 discovery waves D1-D4 — the community leg): the lost-and-
-- found lifecycle state on the neighborhood feed's own posts. A
-- LOST_FOUND post is a live neighborhood fact («مفقودات الحي»): it is
-- ACTIVE while the search is on, and RESOLVED (the poster closed the
-- report) or FOUND (the lost item/being was recovered) when the story
-- ends. The discovery rail reads ACTIVE only (AC-20, the eligibility-
-- first contract: a resolved or recovered report never masquerades as
-- an active one — the state is deterministic eligibility, applied before
-- any ordering or personalization).
--
-- The column is deliberately NULLABLE with NO NOT-NULL CHECK: the state
-- is filled ONLY for category = 'LOST_FOUND' rows (the publish factory
-- stamps ACTIVE; the owner's resolve endpoint moves it to RESOLVED or
-- FOUND) — every other category carries NULL by design, and a CHECK
-- forcing a value would lie about the five non-LOST_FOUND categories.
-- The state vocabulary itself is pinned (D-N7's two-sided discipline:
-- the Java enum LostFoundState mirrors this membership exactly).
--
-- Pattern: the V166/V167 NOT VALID -> separate VALIDATE pair (the V44
-- locking shape). NOT VALID is metadata-only and enforced for every NEW
-- row immediately, so live post writes never wait on a scan; the
-- VALIDATE step rides its OWN migration (V172) under SHARE UPDATE
-- EXCLUSIVE so the shared production database keeps serving traffic
-- during the deploy. The Envers mirror widens in the same migration
-- (the V24/V33 discipline: a base column without its _aud twin breaks
-- audit INSERTs silently).
--
-- The read-rail index rides V173, built CONCURRENTLY outside this
-- transaction, so adding the capability does not block feed writes.
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE neighborhood_posts ADD COLUMN lost_found_state VARCHAR(20) NULL;

-- The Envers mirror widens together (the V24/V33 discipline).
ALTER TABLE neighborhood_posts_aud ADD COLUMN lost_found_state VARCHAR(20);

ALTER TABLE neighborhood_posts
    ADD CONSTRAINT chk_neighborhood_posts_lost_found_state
    CHECK (lost_found_state IN ('ACTIVE', 'RESOLVED', 'FOUND')) NOT VALID;

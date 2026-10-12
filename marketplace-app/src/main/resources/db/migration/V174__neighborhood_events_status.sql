-- JT-20 (#536 discovery waves D1-D4 — the events leg): the neighborhood
-- event's honest gathering state. An event is ACTIVE from birth (the
-- DEFAULT backfills every live V83 row in the same metadata-only step —
-- PostgreSQL 11+ adds a constant-default column without a rewrite), and
-- CANCELLED / POSTPONED are the honest flips: a cancelled or postponed
-- gathering never masquerades as upcoming. The board's upcoming read
-- excludes them (the status-honest discovery contract — the state
-- travels on every card so the client renders it truthfully), while the
-- row, its seats and its audit trail stay: the state is a FACT, not an
-- erasure — no physical delete anywhere in this house.
--
-- The vocabulary is pinned (D-N7's two-sided discipline: the Java enum
-- NeighborhoodEventStatus mirrors this membership exactly). NOT NULL
-- with the DEFAULT: the column is total from day one — every event is
-- in exactly one of the three states, there is no "unknown" gathering.
--
-- Pattern: the V166/V167 NOT VALID -> separate VALIDATE pair (the V44
-- locking shape). NOT VALID is metadata-only and enforced for every NEW
-- row immediately (existing rows all carry the backfilled 'ACTIVE',
-- which the CHECK admits by construction); the VALIDATE step rides its
-- OWN migration (V175) under SHARE UPDATE EXCLUSIVE so the shared
-- production database keeps serving traffic during the deploy. The
-- Envers mirror widens in the same migration (the V24/V33 discipline:
-- a base column without its _aud twin breaks audit INSERTs silently).
--
-- Numbering: V171-V175 — Track B's range (V150-V189).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE neighborhood_events
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';

-- The Envers mirror widens together (the V24/V33 discipline).
ALTER TABLE neighborhood_events_aud ADD COLUMN status VARCHAR(20);

ALTER TABLE neighborhood_events
    ADD CONSTRAINT chk_neighborhood_events_status
    CHECK (status IN ('ACTIVE', 'CANCELLED', 'POSTPONED')) NOT VALID;

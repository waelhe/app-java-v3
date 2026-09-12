-- PostGIS radius search — the expressive GiST index (postgis integration
-- plan §D-P1/D-P5, the fifth governing plan, phase P1).
--
-- The numeric coordinates (V48) REMAIN the single source of truth and the
-- only representation: no geography column, no backfill, no write-path
-- awareness, no new Envers mirror (no base-table column changes — the V33
-- orphan-column lesson cannot even arise here). The radius predicate
-- evaluates the expression
--     ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography
-- — legal for an EXPRESSION index because the geography(geometry) cast is
-- IMMUTABLE in the PostGIS source (the plan's §0.3 legal basis) — and the
-- index is maintained automatically on every write (PostgreSQL
-- expression-index behavior).
--
-- CREATE EXTENSION IF NOT EXISTS: a no-op in fresh environments (the
-- postgis/postgis image's initdb already created it — "Initialize Only on
-- Empty Data Directory") and the explicit creation for the in-place image
-- switch scenario (P-1). Production already carries the extension from the
-- P0 switch (measured: postgis 3.6.4 in pg_extension) — this statement is
-- the idempotent guard that makes the migration self-sufficient on any
-- environment where Flyway runs. postgis_topology is created by the
-- image's initdb in fresh environments (the image's nature — documented,
-- not consumed; plan debt D-I4).
--
-- The partial predicate mirrors the query form exactly (both coordinates
-- present, not soft-deleted): rows without coordinates stay outside the
-- index. ANALYZE follows the index creation per the official guide §4.9.1
-- (ANALYZE is transaction-safe, unlike VACUUM). No CONCURRENTLY in this
-- layer — plan debt D-I1: the table is young and small, and the Railway
-- deploy window covers the lock window exactly like the V44/V47 index
-- precedents; the promotion threshold is a measured 50K rows.
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE INDEX idx_property_details_geog
    ON property_details
    USING gist (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography)
    WHERE latitude IS NOT NULL
      AND longitude IS NOT NULL
      AND is_deleted = FALSE;

ANALYZE property_details;

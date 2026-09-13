-- PostGIS radius search — the CONCURRENTLY rebuild of the V50 index
-- (postgis integration plan §5, debt D-I1 closure — the user's word
-- «فتح D-I1..D-I5», 2026-09-14).
--
-- WHAT THIS CLOSES: D-I1 — "the index is not CONCURRENTLY — it blocks
-- writes while being built (the table is young now)". V50 built
-- idx_property_details_geog non-concurrently (documented, deliberate:
-- the Railway deploy window covered the lock window exactly like the
-- V44/V47 precedents). This migration retires that debt permanently by
-- rebuilding the same index with CONCURRENTLY, so:
--   * the production index's build path never blocks writes again
--     (a future rebuild at >50K rows follows this in-tree precedent);
--   * the official non-transactional migration pattern is proven
--     end-to-end in CI (Testcontainers boots V1..V51 exactly as
--     production does) BEFORE the 50K threshold can arrive.
--
-- OFFICIAL BASIS (PostgreSQL 18 docs, sql-createindex.html):
--   "When this option is used, PostgreSQL will build the index without
--    taking any locks that prevent concurrent inserts, updates, or
--    deletes on the table; whereas a standard index build locks out
--    writes (but not reads) on the table until it's done."
-- And on failure recovery (the same doc's "Building Indexes
-- Concurrently"): a failed concurrent build "will fail but leave
-- behind an 'invalid' index … The recommended recovery method in such
-- cases is to drop the index and try again to perform CREATE INDEX
--    CONCURRENTLY." — i.e. DROP + retry; a FAILED V51 leaves the
-- migration failed (loud, Flyway records it) and the documented
-- recovery is this same drop-and-retry path, never a silent skip.
--
-- FLYWAY OFFICIAL PATTERN (executeInTransaction):
--   PostgreSQL cannot run these statements inside a transaction block,
--   and Flyway documents the script-configuration escape for exactly
--   this class — the sibling file V51__postgis_radius_index_concurrently.sql.conf
--   sets `executeInTransaction = false` (Redgate Flyway docs, "Execute
--   In Transaction Setting": "Note that this setting can be set from
--   Script Configuration in addition to project configuration").
--   Belt AND braces: Flyway 12.4.0's own PostgreSQLParser detects
--   ^(CREATE|DROP)( UNIQUE)? INDEX CONCURRENTLY statements as
--   non-transactional (measured in the flyway-database-postgresql
--   12.4.0 bytecode) — the explicit .conf makes the intent
--   deterministic instead of parser-inferred.
--
-- The index definition is byte-identical to V50's (same name, same
-- parenthesized cast form — index_elem grammar accepts a bare function
-- call or a parenthesized expression, a BARE cast is a syntax error),
-- same partial predicate matching the query form. ANALYZE is NOT
-- repeated here: V50 already gathered the table statistics and a pure
-- index swap changes no table data.
--
-- OWNERSHIP (measured pre-change 2026-09-14 via the one-off psql
-- probe): the current index is owned by `marketplace` (the bootstrap
-- identity that ran V50 on 2026-09-13, before the P3 identity split).
-- V51 executes as `flyway_migrator` (SUPERUSER — ACL checks bypassed,
-- the P3 measured fact), so the DROP succeeds, and the rebuilt index
-- becomes owned by `flyway_migrator` — the identity that owns every
-- V51+ object per the ALTER DEFAULT PRIVILEGES design. Index usage by
-- queries needs no index ACL (the planner uses indexes implicitly).
DROP INDEX CONCURRENTLY idx_property_details_geog;

CREATE INDEX CONCURRENTLY idx_property_details_geog
    ON property_details
    USING gist ((ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography))
    WHERE latitude IS NOT NULL
      AND longitude IS NOT NULL
      AND is_deleted = FALSE;

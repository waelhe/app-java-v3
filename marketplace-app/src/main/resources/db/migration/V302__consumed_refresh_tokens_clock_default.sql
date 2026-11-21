-- The 2026-11-05 #806 round-3 review (Minor · Data Integrity): the
-- consumed_at instant is the reuse-detection window's own arithmetic
-- input, and the window's bound (JdbcRefreshTokenRotationStore) reads
-- the database clock. CURRENT_TIMESTAMP is the TRANSACTION-start time:
-- a commit delayed past the 30s grace window would stamp the
-- consumption at the transaction's beginning, so a post-commit replay
-- of the same value lands INSIDE the grace window by the window's own
-- math and is classified a LATE theft-shaped replay. clock_timestamp()
-- records the true statement instant instead.
--
-- The migration guard's byte-freeze law (MigrationChecksumGuardTest,
-- incident 2026-09-14): V299 stays at its applied content — the schema
-- change rides its own migration.
ALTER TABLE consumed_refresh_tokens
    ALTER COLUMN consumed_at SET DEFAULT clock_timestamp();

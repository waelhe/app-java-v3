-- V31: drop the dead Quartz JDBC job store — 11 tables, zero jobs.
--
-- Evidence: since V29 (PR #205) removed the searchIndexRefresh job — the only
-- job ever registered — the store has ZERO durable jobs (grep: no JobDetail /
-- QuartzJobBean / Scheduler.scheduleJob remains in main code; the only
-- registrations left were test canaries). What still runs on every boot with
-- spring-boot-starter-quartz on the classpath:
--   * the scheduler + its 5-thread pool (idle),
--   * JDBC-store cluster check-ins (qrtz_scheduler_state) — prod sets
--     org.quartz.jobStore.isClustered=true, so a single instance writes
--     STATE_ACCESS / TRIGGER_ACCESS lock rows and recover-scans empty tables,
--   * the QuartzPostgresDelegateConfig machinery (kept only for the b5d9f7f5
--     bytea delegate lesson — the path it guards no longer exists).
-- All actual scheduling is framework-managed already: @Scheduled tasks
-- (EventPublicationCleanup, ExpiredAuthorizationsCleanup,
-- EventPublicationResubmission) and the Modulith staleness monitor run on
-- Spring's task scheduler, which never touches the Quartz store.
-- This batch removes the starter, the config, and the spring.quartz blocks;
-- this migration removes the schema they owned (V21 created all 11 tables).
--
-- Ordering: children first — FK chain from the V21 DDL:
--   QRTZ_SIMPLE_TRIGGERS / QRTZ_CRON_TRIGGERS / QRTZ_SIMPROP_TRIGGERS /
--   QRTZ_BLOB_TRIGGERS -> QRTZ_TRIGGERS -> QRTZ_JOB_DETAILS.
-- QRTZ_FIRED_TRIGGERS, QRTZ_CALENDARS, QRTZ_PAUSED_TRIGGER_GRPS,
-- QRTZ_SCHEDULER_STATE and QRTZ_LOCKS carry no FKs (V21).
-- IF EXISTS keeps the migration idempotent on a store that V29 already
-- emptied of every durable row.
--
-- Same-deploy guarantee: the Quartz starter is removed from the classpath in
-- this same image, so no scheduler instance exists anywhere when Flyway drops
-- its tables (Flyway runs during boot, before any bean could touch them).
-- Like every schema migration in this repo, this is forward-only: a build
-- rollback without a database restore would boot old code expecting V21
-- tables that no longer exist.

DROP TABLE IF EXISTS QRTZ_SIMPLE_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_CRON_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_SIMPROP_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_BLOB_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_FIRED_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_TRIGGERS;
DROP TABLE IF EXISTS QRTZ_JOB_DETAILS;
DROP TABLE IF EXISTS QRTZ_CALENDARS;
DROP TABLE IF EXISTS QRTZ_PAUSED_TRIGGER_GRPS;
DROP TABLE IF EXISTS QRTZ_SCHEDULER_STATE;
DROP TABLE IF EXISTS QRTZ_LOCKS;

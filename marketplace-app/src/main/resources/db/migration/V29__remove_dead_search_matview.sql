-- V29: remove dead search machinery — the never-read materialized view and its
-- 5-minute Quartz refresh job.
--
-- Evidence: mv_listing_search (created by V9) is refreshed every 300 seconds by
-- the searchIndexRefresh Quartz job, but NO query reads it — full-text search
-- reads provider_listings directly through the GIN index idx_listing_search_title
-- (ProviderListingRepository.searchFullText). The refresh was pure production
-- load with zero readers. The GIN index stays; only the dead view and its
-- refresh work are removed.
--
-- Quartz rows: the JDBC job store persists durable jobs (QRTZ_JOB_DETAILS).
-- Leaving these rows after deleting the SearchIndexRefresher class would make
-- every trigger fire fail with ClassNotFoundException forever (misfire
-- rescheduling included). Spring Boot guarantees Flyway runs before the Quartz
-- scheduler starts (spring-boot-quartz 4.1.1
-- SchedulerDependsOnDatabaseInitializationDetector registers the
-- SchedulerFactoryBean to depend on database initialization), so deleting the
-- rows here is race-free. Child tables first — FK chain:
-- QRTZ_SIMPLE_TRIGGERS -> QRTZ_TRIGGERS -> QRTZ_JOB_DETAILS (V21 DDL).
-- Group "DEFAULT": JobDetailFactoryBean/TriggerFactoryBean default; job/trigger
-- names from SearchIndexQuartzConfig (names preserved in git history);
-- SCHED_NAME from application.yml org.quartz.scheduler.instanceName.

DELETE FROM QRTZ_SIMPLE_TRIGGERS
 WHERE SCHED_NAME = 'marketplaceScheduler'
   AND TRIGGER_NAME = 'searchIndexRefreshTrigger'
   AND TRIGGER_GROUP = 'DEFAULT';

DELETE FROM QRTZ_TRIGGERS
 WHERE SCHED_NAME = 'marketplaceScheduler'
   AND TRIGGER_NAME = 'searchIndexRefreshTrigger'
   AND TRIGGER_GROUP = 'DEFAULT';

DELETE FROM QRTZ_JOB_DETAILS
 WHERE SCHED_NAME = 'marketplaceScheduler'
   AND JOB_NAME = 'searchIndexRefreshJob'
   AND JOB_GROUP = 'DEFAULT';

-- The matview and its indexes (V9 lines 2-17). Dropping a matview drops its
-- indexes; the explicit IF EXISTS drops keep this idempotent and leave no
-- leftovers if the view were already gone.
DROP INDEX IF EXISTS idx_mv_listing_search_category;
DROP INDEX IF EXISTS idx_mv_listing_search_id;
DROP MATERIALIZED VIEW IF EXISTS mv_listing_search;

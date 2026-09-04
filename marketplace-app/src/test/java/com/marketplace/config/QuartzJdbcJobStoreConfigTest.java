package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.Job;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobExecutionContext;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.SimpleScheduleBuilder;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Regression test for the Quartz JDBC job store against real PostgreSQL.
 *
 * <p>The {@code test} profile intentionally runs Quartz with
 * {@code job-store-type: memory} + {@code auto-startup: false} for speed, which
 * leaves the JDBC job store path completely untested: its first real execution
 * was the b5d9f7f5 Railway production deploy, which crash-looped during
 * {@code SchedulerFactoryBean} job registration with
 * <pre>
 * Couldn't store trigger 'DEFAULT.searchIndexRefreshTrigger' for
 * 'DEFAULT.searchIndexRefreshJob' job: Couldn't retrieve job:
 * Bad value for type long : \xaced…
 * </pre>
 * The default {@code StdJDBCDelegate} reads the {@code JOB_DATA} column via
 * {@code ResultSet.getBlob} — pgjdbc interprets a BYTEA column as a large-object
 * OID and fetches it as a long. The fix configures the delegate the official
 * Quartz PostgreSQL DDL (V21's source) documents:
 * {@code org.quartz.impl.jdbcjobstore.PostgreSQLDelegate}, which reads bytea
 * with {@code getBytes}.
 *
 * <p>Vehicle change (V29): the search index refresh job this test originally
 * rode on was removed — {@code mv_listing_search} had no readers, and the
 * durable job/trigger rows are deleted by V29 before the scheduler starts
 * (spring-boot-quartz 4.1.1 SchedulerDependsOnDatabaseInitializationDetector
 * registers the SchedulerFactoryBean to depend on database initialization).
 * The JOB_DATA blob roundtrip through the configured delegate is still
 * exercised end-to-end: this test registers its own durable job + trigger at
 * runtime — {@code scheduleJob} stores the trigger and retrieves the job
 * (JOB_DATA bytea) through the JDBC store, the exact path that crash-looped
 * b5d9f7f5.
 *
 * <p>This test boots the full application context with the JDBC job store on a
 * real PostgreSQL (same {@code postgres:18-alpine} image as CI services and
 * docker-compose), with Flyway migrations applied — V21 creates the QRTZ
 * schema, V29 removes the dead search refresh job/trigger rows.
 */
@SpringBootTest(properties = {
        "spring.quartz.job-store-type=jdbc",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class QuartzJdbcJobStoreConfigTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    Scheduler scheduler;

    @Test
    void removedSearchRefreshJobIsGoneFromJdbcStore() throws SchedulerException {
        // V29 deletes the durable rows; the beans/classes are gone so nothing
        // re-registers them. checkExists queries the JDBC store.
        assertThat(scheduler.checkExists(JobKey.jobKey("searchIndexRefreshJob")))
                .as("dead search refresh job removed from the JDBC job store (V29)")
                .isFalse();
        assertThat(scheduler.checkExists(TriggerKey.triggerKey("searchIndexRefreshTrigger")))
                .as("dead search refresh trigger removed from the JDBC job store (V29)")
                .isFalse();
    }

    @Test
    void durableJobRoundTripsThroughJdbcStore() throws SchedulerException {
        JobKey jobKey = JobKey.jobKey("delegateRoundtripJob", "TEST");
        TriggerKey triggerKey = TriggerKey.triggerKey("delegateRoundtripTrigger", "TEST");

        JobDetail detail = JobBuilder.newJob(NoopJob.class)
                .withIdentity(jobKey)
                .withDescription("JOB_DATA bytea roundtrip through the JDBC job store")
                .storeDurably()
                .build();
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey)
                .forJob(jobKey)
                .withSchedule(SimpleScheduleBuilder.repeatSecondlyForever(3600)
                        .withMisfireHandlingInstructionIgnoreMisfires())
                .build();

        try {
            // addJob writes the JOB_DATA bytea; scheduleJob stores the trigger and
            // retrieves the job through the delegate — the b5d9f7f5 crash path.
            scheduler.addJob(detail, /* replace */ true);
            scheduler.scheduleJob(trigger);

            assertThat(scheduler.checkExists(jobKey))
                    .as("durable job persisted through the JDBC job store")
                    .isTrue();
            assertThat(scheduler.checkExists(triggerKey))
                    .as("trigger persisted through the JDBC job store")
                    .isTrue();
        } finally {
            // Remove both rows — leaves the store exactly as V29 expects it.
            scheduler.deleteJob(jobKey);
        }
    }

    /** No-op job — instantiated by the scheduler's SpringBeanJobFactory. */
    static class NoopJob implements Job {
        @Override
        public void execute(JobExecutionContext context) {
            // no-op: this job exists only to exercise the JDBC store roundtrip
        }
    }
}

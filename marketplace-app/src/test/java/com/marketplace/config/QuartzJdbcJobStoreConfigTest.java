package com.marketplace.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
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
 * left the JDBC job store path completely untested: its first real execution was
 * the b5d9f7f5 Railway production deploy, which crash-looped during
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
 * <p>This test boots the full application context with the JDBC job store on a
 * real PostgreSQL (same {@code postgres:18-alpine} image as CI services and
 * docker-compose), with Flyway migrations applied — V21 creates the QRTZ
 * schema. Registering {@code SearchIndexQuartzConfig}'s durable job + trigger
 * writes and reads back the {@code JOB_DATA} blob through the configured
 * delegate during context refresh, so a delegate misconfiguration fails this
 * test at boot.
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
    void durableJobAndTriggerRegisteredThroughJdbcStore() throws SchedulerException {
        // checkExists queries the JDBC store — the job row written (and its JOB_DATA
        // blob read back) during registration must be retrievable.
        assertThat(scheduler.checkExists(JobKey.jobKey("searchIndexRefreshJob")))
                .as("durable Quartz job persisted through the JDBC job store")
                .isTrue();
        assertThat(scheduler.checkExists(
                org.quartz.TriggerKey.triggerKey("searchIndexRefreshTrigger")))
                .as("search index refresh trigger persisted through the JDBC job store")
                .isTrue();
    }
}

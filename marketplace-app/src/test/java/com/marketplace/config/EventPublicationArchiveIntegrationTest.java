package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.moments.DayHasPassed;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the event publication registry's {@code ARCHIVE}
 * completion mode ({@code spring.modulith.events.completion-mode: archive},
 * application.yml) against the <em>real Flyway schema</em>.
 *
 * <p>Root cause this guards: {@code V11__modulith_events.sql} created
 * {@code event_publication} only, never the archive relation the ARCHIVE
 * completion mode moves completed publications into. Production runs
 * {@code ddl-auto: none} (Flyway owns the schema), so every publication
 * completion failed with
 * <pre>
 * ERROR: relation "event_publication_archive" does not exist
 * </pre>
 * (live evidence: deployment {@code 51b5496d} runtime logs, 2026-09-04 —
 * at Moments' boot-time {@code DayHasPassed} catch-up), leaving
 * publications permanently incomplete. The {@code test} profile masked the
 * gap: {@code application-test.yml} disables Flyway and runs
 * {@code ddl-auto: create-drop}, so tests build the table from the
 * {@code ArchivedJpaEventPublication} entity mapping rather than from
 * migrations — CI stayed green while production failed. The fix is
 * {@code V28__modulith_event_archive.sql} with the official Spring Modulith
 * 2.1.1 PostgreSQL "Archive-enabled schema" (Reference appendix —
 * https://docs.spring.io/spring-modulith/reference/appendix.html).
 *
 * <p>This test follows the {@code QuartzJdbcJobStoreConfigTest} pattern: boot
 * the full application context on real PostgreSQL ({@code postgres:18-alpine},
 * same image as CI services and docker-compose) with Flyway enabled and
 * {@code ddl-auto=none}, so the registry runs against exactly the schema
 * migrations produce. Publishing a {@code DayHasPassed} (the production
 * event that exposed the failure) exercises the full completion path:
 * listener execution, archive insert, completion date stamp, and removal of
 * the original row — so a missing or divergent migration fails this test.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventPublicationArchiveIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void completedEventPublicationsAreArchivedThroughFlywaySchema() {
        long archivedBefore = countArchived("onDayHasPassed");

        // @ApplicationModuleListener dispatch is AFTER_COMMIT + async: the
        // publication must be created inside a committed transaction for the
        // listeners to run (a test-managed rollback transaction would never
        // fire them).
        transactions.executeWithoutResult(tx ->
                events.publishEvent(DayHasPassed.of(LocalDate.now(ZoneOffset.UTC))));

        // Completion marking runs after each listener's REQUIRES_NEW
        // transaction commits (CompletionRegisteringAdvisor order
        // HIGHEST_PRECEDENCE + 10 wraps the transaction interceptor), so the
        // archive rows appear asynchronously — poll for both listeners.
        awaitArchiveCount(archivedBefore + 2, "both DayHasPassed listeners completed and archived");

        // Official semantics (Reference, "Event Publication Completion"): "For
        // that archive entry, the completion date is set and the original entry
        // is removed."
        assertThat(countArchived("AvailabilityService%onDayHasPassed"))
                .as("availability listener's publication archived")
                .isGreaterThanOrEqualTo(1);
        assertThat(countArchived("BookingExpirationService%onDayHasPassed"))
                .as("booking expiration listener's publication archived")
                .isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from event_publication_archive where completion_date is not null",
                Long.class))
                .as("archived publications carry a stamped completion date")
                .isGreaterThanOrEqualTo(2);
        assertThat(countLive("onDayHasPassed"))
                .as("original publication rows removed from event_publication")
                .isZero();
    }

    private long countArchived(String listenerPattern) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication_archive where listener_id like ?",
                Long.class, "%" + listenerPattern + "%");
        return count == null ? 0 : count;
    }

    private long countLive(String listenerPattern) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication where listener_id like ?",
                Long.class, "%" + listenerPattern + "%");
        return count == null ? 0 : count;
    }

    /**
     * Polls (up to 30s, 200ms interval) until the archive holds the expected
     * number of {@code onDayHasPassed} rows; fails with a diagnostic message
     * otherwise. Plain poll loop — no Awaitility dependency in this reactor.
     */
    private void awaitArchiveCount(long expected, String description) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        long last = -1;
        while (System.nanoTime() < deadline) {
            last = countArchived("onDayHasPassed");
            if (last >= expected) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError(String.format(
                "%s — expected >= %d archived rows, found %d after 30s. "
                        + "If the archive relation is missing from db/migration the "
                        + "completion insert fails silently in the async listener thread.",
                description, expected, last));
    }
}

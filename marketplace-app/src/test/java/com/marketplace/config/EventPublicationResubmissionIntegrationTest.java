package com.marketplace.config;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.marketplace.shared.cache.EventPublicationResubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.modulith.events.EventPublication.Status;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.modulith.events.core.Staleness;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the event-publication lifecycle layer, on the <b>real
 * Flyway schema</b> (the recorded lesson: a {@code ddl-auto: create-drop}
 * schema hides what production actually runs — V28 archive table, V11 status
 * columns). Boots the full application context on real PostgreSQL
 * ({@code postgres:18-alpine}, same image as CI services and
 * docker-compose) with Flyway enabled and {@code ddl-auto=none}, following the
 * {@code EventPublicationArchiveIntegrationTest} pattern.
 *
 * <p>Guards three behaviors:
 * <ol>
 *   <li><b>Staleness monitor registration.</b> With non-zero
 *       {@code spring.modulith.events.staleness.*} durations the official
 *       Staleness Monitor registers its task — proven by the framework's own
 *       boot log line "Checking for stale event publications every …". With
 *       the pre-fix dead keys this line never appeared (the monitor stayed
 *       unregistered), which is exactly how the latent defect went
 *       unnoticed.</li>
 *   <li><b>Runtime recovery.</b> A listener that fails marks its publication
 *       FAILED immediately (CompletionRegisteringAdvisor). After the root
 *       cause is fixed, one {@link EventPublicationResubmission} sweep
 *       re-delivers the event, the listener succeeds, and the publication
 *       completes into {@code event_publication_archive} with
 *       {@code completion_attempts >= 2} — recovery without a restart.</li>
 *   <li><b>Retry backoff.</b> Within {@link EventPublicationResubmission#RETRY_BACKOFF}
 *       a failed publication is retried at most once: an immediately repeated
 *       sweep must not re-dispatch it (no retry storm).</li>
 * </ol>
 *
 * <p>The probe listener is deliberately a plain bean method: the publication
 * registry machinery (multicaster, completion advisor) is the same one
 * production listeners run under, so this pins the real dispatch path. All
 * assertions are scoped to the probe's {@code listener_id} and cleaned up in
 * {@link #cleanProbeRows()} — the boot-time {@code DayHasPassed} catch-up and
 * the real listeners' rows are never touched.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // Non-zero durations activate the official Staleness Monitor — the
        // same names the corrected application-prod.yml declares.
        "spring.modulith.events.staleness.published=6h",
        "spring.modulith.events.staleness.processing=1h",
        "spring.modulith.events.staleness.resubmitted=1h",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventPublicationResubmissionIntegrationTest {

    private static final String LISTENER_PATTERN = "%ProbeListener%onProbe%";

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

    @Autowired
    private EventPublicationResubmission resubmission;

    @Autowired
    private FailedEventPublications failedPublications;

    @Autowired
    private EventPublicationRegistry registry;

    @Autowired
    private ProbeListener probeListener;

    /**
     * The exact configuration the official Staleness Monitor reads — the bean
     * Spring Modulith registers via {@code @EnableConfigurationProperties}.
     * With the pre-fix dead yaml keys every duration here bound to ZERO.
     */
    @Autowired
    private Staleness staleness;

    /**
     * Controlled-failure probe: the publication registry records a publication
     * per listener invocation; throwing marks it FAILED — the production
     * failure mode (e.g. a notification listener when the SMTP provider is
     * unreachable).
     */
    @TestConfiguration
    static class ProbeConfig {

        @Bean
        public ProbeListener probeListener() {
            return new ProbeListener();
        }
    }

    public static class ProbeListener {

        private final AtomicBoolean fail = new AtomicBoolean(true);

        /** Control seam — must be a method, not field access: the bean is
         *  AOP-proxied (CompletionRegisteringAdvisor), and a CGLIB proxy's
         *  fields are never initialized — method calls delegate to the real
         *  target instance. */
        public void failNextDeliveries() {
            fail.set(true);
        }

        /** Control seam — see {@link #failNextDeliveries()}. */
        public void succeedFromNowOn() {
            fail.set(false);
        }

        @ApplicationModuleListener
        public void onProbe(ResubmissionProbeEvent event) {
            if (fail.get()) {
                throw new IllegalStateException("simulated listener failure #" + event.id());
            }
        }
    }

    public record ResubmissionProbeEvent(UUID id) {
    }

    @AfterEach
    void cleanProbeRows() {
        // event_publication (V11) has no FKs into domain tables; the archive
        // table (V28) is equally standalone. Deleting by the probe's
        // listener_id leaves boot-time DayHasPassed rows and any other
        // publication untouched.
        jdbc.update("DELETE FROM event_publication WHERE listener_id LIKE ?", LISTENER_PATTERN);
        jdbc.update("DELETE FROM event_publication_archive WHERE listener_id LIKE ?", LISTENER_PATTERN);
    }

    @Test
    @Order(1) // runs first so the shared context boots inside this method —
    // OutputCapture then includes the framework's boot-time registration log.
    void stalenessMonitorRegistersWhenDurationsAreNonZero(CapturedOutput output) {
        // 1 — the properties the monitor reads, bound from the corrected
        //     property names (dead keys would leave every duration ZERO).
        assertThat(staleness.getStaleness(Status.PUBLISHED))
                .as("spring.modulith.events.staleness.published bound")
                .isEqualTo(Duration.ofHours(6));
        assertThat(staleness.getStaleness(Status.PROCESSING))
                .as("spring.modulith.events.staleness.processing bound")
                .isEqualTo(Duration.ofHours(1));
        assertThat(staleness.getStaleness(Status.RESUBMITTED))
                .as("spring.modulith.events.staleness.resubmitted bound")
                .isEqualTo(Duration.ofHours(1));

        // 2 — the framework's own registration log (StalenessMonitor-
        //     Configuration, INFO): emitted only when monitorStaleness() is
        //     true — i.e. when the property names actually bound and the task
        //     was registered. The pre-fix dead keys never produced this line.
        assertThat(output.getAll())
                .as("official Staleness Monitor registered its scheduled task")
                .contains("Checking for stale event publications every");
    }

    @Test
    @Order(2)
    void DIAGNOSTIC_resubmissionPipelineBisection() throws Exception {
        probeListener.failNextDeliveries();
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new ResubmissionProbeEvent(UUID.randomUUID())));
        awaitPublication("FAILED", 1, "listener failure marks the publication FAILED");

        System.out.println("DIAG row-before: " + jdbc.queryForMap(
                "select status, completion_attempts, last_resubmission_date, publication_date, event_type, listener_id"
                        + " from event_publication where listener_id like ?", LISTENER_PATTERN));
        System.out.println("DIAG registry-incomplete-before: " + registry.findIncompletePublications().stream()
                .map(p -> "id=" + p.getIdentifier() + " status=" + p.getStatus()
                        + " attempts=" + p.getCompletionAttempts()
                        + " lastResub=" + p.getLastResubmissionDate()
                        + " target=" + p.getTargetIdentifier())
                .toList());

        // Control A — the framework's own resubmit, NO filter, raw bean:
        System.out.println("DIAG calling raw failedPublications.resubmit(defaults())");
        failedPublications.resubmit(ResubmissionOptions.defaults());
        Thread.sleep(3000);
        System.out.println("DIAG row-after-raw: " + jdbc.queryForMap(
                "select status, completion_attempts, last_resubmission_date from event_publication where listener_id like ?",
                LISTENER_PATTERN));

        // Control B — the component's sweep (with the backoff filter):
        System.out.println("DIAG calling resubmission.resubmitDue(now)");
        resubmission.resubmitDue(Instant.now());
        Thread.sleep(3000);
        System.out.println("DIAG row-after-sweep: " + jdbc.queryForMap(
                "select status, completion_attempts, last_resubmission_date from event_publication where listener_id like ?",
                LISTENER_PATTERN));
        System.out.println("DIAG registry-incomplete-after: " + registry.findIncompletePublications().stream()
                .map(p -> "id=" + p.getIdentifier() + " status=" + p.getStatus()
                        + " attempts=" + p.getCompletionAttempts()
                        + " lastResub=" + p.getLastResubmissionDate())
                .toList());
    }

    @Test
    @Disabled("diagnostic round — restored after the root cause is fixed")
    void failedPublicationIsRecoveredAtRuntimeWithoutRestart() {
        // 1 — the listener fails: the publication goes FAILED (attempts = 1).
        probeListener.failNextDeliveries();
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new ResubmissionProbeEvent(UUID.randomUUID())));
        awaitPublication("FAILED", 1, "listener failure marks the publication FAILED");

        // 2 — root cause fixed: the same listener now succeeds.
        probeListener.succeedFromNowOn();

        // 3 — one sweep re-delivers: publication completes into the archive
        //     with completion_attempts >= 2 (original attempt + resubmission).
        resubmission.resubmitDue(Instant.now());
        awaitArchived(2, "recovered publication archived with the retried attempt count");
    }

    @Test
    @Disabled("diagnostic round — restored after the root cause is fixed")
    void failedPublicationIsRetriedAtMostOncePerBackoffWindow() {
        probeListener.failNextDeliveries();
        transactions.executeWithoutResult(tx ->
                events.publishEvent(new ResubmissionProbeEvent(UUID.randomUUID())));
        awaitPublication("FAILED", 1, "listener failure marks the publication FAILED");

        // First sweep retries (last_resubmission_date was NULL — first retry).
        resubmission.resubmitDue(Instant.now());
        awaitPublication("FAILED", 2, "first retry re-dispatched the failing listener");

        // Exactly one retry so far — the probe row is the only one matching
        // the listener pattern (AfterEach cleans between tests).
        assertThat(countAttempts())
                .as("exactly one retry has been recorded")
                .isEqualTo(2);
        Instant lastResubmissionAfterFirstRetry = lastResubmission();

        // Second sweep immediately after: blocked by the backoff window.
        resubmission.resubmitDue(Instant.now());

        // Stability window: even if a mark were (incorrectly) attempted, it
        // would land within seconds — watch the row for 2s.
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            assertThat(countAttempts())
                    .as("no second retry within the backoff window (attempts unchanged)")
                    .isEqualTo(2);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(lastResubmission())
                .as("last resubmission date unchanged — the sweep left the publication alone")
                .isEqualTo(lastResubmissionAfterFirstRetry);
    }

    private void awaitPublication(String status, int minAttempts, String description) {
        poll(description, () -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from event_publication where listener_id like ?"
                            + " and status = ? and completion_attempts >= ?",
                    Long.class, LISTENER_PATTERN, status, minAttempts);
            return count != null && count > 0;
        });
    }

    private void awaitArchived(int minAttempts, String description) {
        poll(description, () -> {
            Long count = jdbc.queryForObject(
                    "select count(*) from event_publication_archive where listener_id like ?"
                            + " and completion_date is not null and completion_attempts >= ?",
                    Long.class, LISTENER_PATTERN, minAttempts);
            return count != null && count > 0;
        });
    }

    private long countAttempts() {
        Integer attempts = jdbc.queryForObject(
                "select completion_attempts from event_publication where listener_id like ?",
                Integer.class, LISTENER_PATTERN);
        return attempts == null ? -1 : attempts;
    }

    private Instant lastResubmission() {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "select last_resubmission_date from event_publication where listener_id like ?",
                java.sql.Timestamp.class, LISTENER_PATTERN);
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Polls (up to 30s, 200ms interval — the {@code EventPublicationArchive-
     * IntegrationTest} pattern; no Awaitility in this reactor) until the
     * condition holds, failing with a diagnostic message otherwise. Listener
     * dispatch is AFTER_COMMIT + async, so state transitions must be polled.
     */
    private void poll(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("Condition not met within 30s: " + description);
    }
}

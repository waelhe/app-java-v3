package com.marketplace.config;

import test.config.IntegrationContainers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-13 (compliance plan D.2): the restart-republish gate — «سياسة إعادة نشر
 * الأحداث المعلقة» ({@code spring.modulith.events.republish-outstanding-events-
 * on-restart}), proven end-to-end on the <b>real Flyway schema</b> (the same
 * boot the production deployment rides: the property is declared in
 * {@code application-prod.yml} and {@code application-staging.yml}).
 *
 * <p><b>The journey under proof.</b> Phase 1 publishes a probe event the way
 * Moments publishes its passage-of-time events — <i>with no transaction</i>
 * around the publish. The registry stores the publication row
 * synchronously (status {@code PUBLISHED}, {@code completion_date} null),
 * but the {@code @ApplicationModuleListener} is <b>not invoked at
 * publication time</b>: the official Framework rule for
 * {@code @TransactionalEventListener} — "if no transaction is running, the
 * listener is not invoked at all" — defers delivery to the recovery
 * machinery. That is exactly the pending-at-shutdown state the restart
 * policy exists for (it is also the delivery shape the availability and
 * booking listeners live on for Moments' {@code DayHasPassed}). Phase 2 is
 * the restart itself: {@link DirtiesContext @DirtiesContext} rebuilds the
 * application context against the same PostgreSQL, the framework's
 * {@code PersistentApplicationEventMulticaster.afterSingletonsInstantiated()}
 * sees {@code republish-outstanding-events-on-restart=true} and re-delivers
 * every incomplete publication through {@code processEvent} (the direct
 * path that bypasses the transactional-phase check), the fresh context's
 * probe listener receives the event, and the publication completes into
 * {@code event_publication_archive} (the {@code archive} completion mode).
 *
 * <p><b>Deliberate isolation:</b> staleness durations stay zero (the
 * default) so the Staleness Monitor is NOT registered — the pending row
 * must be delivered by the BOOT republish alone, not by the
 * staleness-to-FAILED chain the {@code EventPublicationResubmission-
 * IntegrationTest} already guards. There is also deliberately NO
 * {@code @AfterEach} cleanup: the pending publication has to survive the
 * context reboot — the test's own subject. The probe rows are cleaned at
 * the end of phase 2, and the class owns its container (the
 * {@code PlatformGovernanceFilesTest} per-class isolation rule), so even a
 * failed phase 2 cannot leak rows into a neighboring class.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The D.2 property under proof — the official restart republish.
        "spring.modulith.events.republish-outstanding-events-on-restart=true",
        // Staleness deliberately zero (the default): the monitor stays
        // unregistered so the BOOT republish is the only delivery path.
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventPublicationRestartRepublishIntegrationTest {

    private static final String LISTENER_PATTERN = "%RestartProbeListener%onProbe%";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RestartProbeListener probeListener;

    /** The event id published in phase 1 — static: JUnit creates a fresh test instance per method. */
    private static UUID publishedEventId;

    @TestConfiguration
    static class ProbeConfig {

        @Bean
        public RestartProbeListener restartProbeListener() {
            return new RestartProbeListener();
        }
    }

    /**
     * Controlled-receipt probe — the {@code EventPublicationResubmission-
     * IntegrationTest} shape: an {@code @ApplicationModuleListener} (the
     * registry-tracked, async, {@code REQUIRES_NEW} listener the production
     * Moment-fed listeners use), so the completion advisor and archive
     * machinery run the exact production dispatch path.
     */
    public static class RestartProbeListener {

        private final AtomicReference<UUID> receipt = new AtomicReference<>();

        /** Control seam — a method, not field access: the bean is AOP-proxied. */
        public UUID receipt() {
            return receipt.get();
        }

        @ApplicationModuleListener
        public void onProbe(RestartProbeEvent event) {
            receipt.set(event.id());
        }
    }

    public record RestartProbeEvent(UUID id) {
    }

    @Test
    @Order(1)
    @DirtiesContext // THE restart — the pending publication must survive into a fresh context
    void aNonTransactionalPublicationStaysPendingUntilTheRestart() {
        publishedEventId = UUID.randomUUID();

        // Moments' own publication shape: NO transaction around the publish.
        events.publishEvent(new RestartProbeEvent(publishedEventId));

        // The row is stored synchronously (the multicaster's store runs in the
        // publish call stack, its own JPA transaction).
        poll("the publication row is stored", () -> countPending() == 1);

        // ...but the listener is NOT invoked — the official Framework rule.
        assertThat(probeListener.receipt())
                .as("no delivery at publication time: @TransactionalEventListener without a running transaction")
                .isNull();

        // The row sits PUBLISHED, incomplete — the pending-at-shutdown state.
        assertThat(pendingStatus())
                .as("the stored-but-undelivered publication's initial state")
                .isEqualTo("PUBLISHED");
    }

    @Test
    @Order(2)
    void theBootRepublishDeliversThePendingPublication() {
        // Context B booted with republish-outstanding-events-on-restart=true;
        // the boot republish delivers the pending row (async + REQUIRES_NEW),
        // the listener records the receipt, the publication completes into the
        // archive (completion-mode: archive) and leaves event_publication.
        poll("the pending publication completed into the archive", () -> archivedFor(publishedEventId) == 1);

        assertThat(probeListener.receipt())
                .as("the fresh context's listener received the event published before the restart")
                .isEqualTo(publishedEventId);
        assertThat(countPending())
                .as("the delivered publication left event_publication")
                .isZero();

        cleanProbeRows();
    }

    // ---------- helpers (the EventPublicationResubmissionIntegrationTest patterns) ----------

    private long countPending() {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication where listener_id like ?",
                Long.class, LISTENER_PATTERN);
        return count == null ? 0 : count;
    }

    private String pendingStatus() {
        return jdbc.queryForObject(
                "select status from event_publication where listener_id like ?",
                String.class, LISTENER_PATTERN);
    }

    private long archivedFor(UUID eventId) {
        Long count = jdbc.queryForObject(
                "select count(*) from event_publication_archive"
                        + " where listener_id like ? and serialized_event like ?"
                        + " and completion_date is not null",
                Long.class, LISTENER_PATTERN, "%" + eventId + "%");
        return count == null ? 0 : count;
    }

    private void cleanProbeRows() {
        jdbc.update("DELETE FROM event_publication WHERE listener_id LIKE ?", LISTENER_PATTERN);
        jdbc.update("DELETE FROM event_publication_archive WHERE listener_id LIKE ?", LISTENER_PATTERN);
    }

    /**
     * Polls (up to 30s, 200ms interval — the house pattern; no Awaitility in
     * this reactor) until the condition holds, failing with a diagnostic
     * message otherwise. Boot-republish delivery is async, so state
     * transitions must be polled.
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

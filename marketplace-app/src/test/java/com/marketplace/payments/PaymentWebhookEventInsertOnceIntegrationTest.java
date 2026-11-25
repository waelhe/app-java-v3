package com.marketplace.payments;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The webhook inbox's deduplication CONTRACT, executed against the REAL
 * migration schema — the NotificationInboxLedgerInsertOnceIntegrationTest
 * discipline (spring.flyway.enabled=true + ddl-auto=none), because the
 * contract's serialization point is migration-owned, not entity-owned:
 *
 * <ol>
 *   <li><b>The V42 composite unique key</b> —
 *       {@code payment_webhook_events_provider_event_id_key} on
 *       (provider, event_id). A create-drop entity schema never creates it
 *       (the entity carries no JPA unique constraint), so every
 *       {@code DataIntegrityViolationException} the service's catch-arm
 *       expects can NEVER happen there — the measured gap this class
 *       closes: the existing {@code PaymentWebhookEventServiceTest} race
 *       test throws the exception from a hand-rolled mock, so it cannot
 *       fail when the index is missing from the actual schema (a rebuilt
 *       table, a drifted environment). The ledger law needs the schema
 *       that owns the ledger.</li>
 *   <li><b>Provider scoping (B5)</b> — the same {@code event_id} under a
 *       different provider is a DIFFERENT event: the composite key admits
 *       it, exactly why the pre-check and the post-violation re-check are
 *       provider-scoped in the service.</li>
 *   <li><b>The insert is the serialization point</b> — two concurrent
 *       deliveries of the same (provider, event_id) on two real threads
 *       with two real REQUIRES_NEW transactions: exactly one insert wins,
 *       the loser surfaces {@code DataIntegrityViolationException} at its
 *       own transactional boundary (the exact contract
 *       {@code WebhookEventRecorder.record} documents: the loser is
 *       answered "already processed", never a 5xx).</li>
 * </ol>
 *
 * <p>The module slice's own tests (PaymentsModuleIntegrationTest and the
 * webhook service unit tests) keep the dispatch behaviors on the
 * create-drop schema — their contracts live in the entity mapping and
 * the service logic. THIS class holds what only the migrated database can
 * prove: the unique index the whole dedup gate stands on.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PaymentWebhookEventInsertOnceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; the established IntegrationContainers factory shape.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate requiresNewTemplate() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * The dedup contract's deterministic leg on the real migration chain
     * (V17 + V42): the first (provider, event_id) insert commits; the
     * duplicate is REJECTED by the composite unique key as
     * {@code DataIntegrityViolationException} (the exception the service's
     * catch-arm answers "already processed"); and the same {@code event_id}
     * under a different provider is a different event and inserts fine —
     * the B5 provider scoping, measured on the schema that owns it.
     */
    @Test
    void duplicateProviderEventIdIsRejectedByTheV42Key_whileAnotherProviderAdmitsTheSameEventId() {
        String eventId = "evt_" + UUID.randomUUID();
        webhookEventRepository.saveAndFlush(PaymentWebhookEvent.create(
                "stripe", eventId, "payment_intent.succeeded",
                "{\"id\":\"" + eventId + "\"}", null, "pi_external", 0L));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events WHERE provider = ? AND event_id = ?",
                Integer.class, "stripe", eventId)).isEqualTo(1);

        assertThatThrownBy(() -> webhookEventRepository.saveAndFlush(PaymentWebhookEvent.create(
                "stripe", eventId, "payment_intent.succeeded",
                "{\"id\":\"" + eventId + "\", \"dup\":true}", null, "pi_external", 0L)))
                .as("the V42 composite unique key rejects the duplicate (provider, event_id) "
                        + "insert — the serialization point the service's catch-arm stands on")
                .isInstanceOf(DataIntegrityViolationException.class);

        // B5 provider scoping, on the schema that owns it: the same event_id
        // under a different provider is a different event and INSERTS fine.
        webhookEventRepository.saveAndFlush(PaymentWebhookEvent.create(
                "legacy-hmac", eventId, "payment_intent.succeeded",
                null, null, null, null));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_webhook_events WHERE event_id = ?",
                Integer.class, eventId)).as("one row per provider for the same event_id")
                .isEqualTo(2);
    }

    /**
     * The insert-as-serialization-point leg — the R10/WebhookEventRecorder
     * contract on two REAL threads with two REAL REQUIRES_NEW transactions
     * against PostgreSQL: two concurrent deliveries of the same
     * (provider, event_id) both attempt the inbox insert; the V42 key lets
     * exactly ONE commit, the loser surfaces
     * {@code DataIntegrityViolationException} at its own transactional
     * boundary and rolls back leaving no row — exactly ONE row survives,
     * the loser's answer is the exception arm the service answers
     * "already processed", never a 5xx.
     */
    @Test
    void concurrentDeliveriesOfTheSameEventSerializeOnTheInsert_exactlyOneRowSurvives() throws Exception {
        String eventId = "evt_" + UUID.randomUUID();
        CountDownLatch startTogether = new CountDownLatch(1);
        AtomicReference<DataIntegrityViolationException> loserFailure = new AtomicReference<>();
        ExecutorService couriers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = couriers.submit(() -> deliver("stripe", eventId, startTogether, loserFailure));
            Future<Boolean> second = couriers.submit(() -> deliver("stripe", eventId, startTogether, loserFailure));
            startTogether.countDown();
            boolean firstWon = first.get(30, TimeUnit.SECONDS);
            boolean secondWon = second.get(30, TimeUnit.SECONDS);

            assertThat(firstWon ^ secondWon)
                    .as("exactly one of the two concurrent deliveries wins the insert")
                    .isTrue();
            assertThat(loserFailure.get())
                    .as("the losing delivery surfaces DataIntegrityViolationException at its "
                            + "own REQUIRES_NEW boundary — the arm the service answers "
                            + "'already processed' with")
                    .isNotNull();
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM payment_webhook_events WHERE provider = ? AND event_id = ?",
                    Integer.class, "stripe", eventId))
                    .as("exactly ONE inbox row survives the concurrent duplicate deliveries")
                    .isEqualTo(1);
        } finally {
            couriers.shutdownNow();
        }
    }

    /** One delivery's insert attempt, in its own REQUIRES_NEW transaction. */
    private boolean deliver(String provider, String eventId, CountDownLatch startTogether,
                             AtomicReference<DataIntegrityViolationException> loserFailure) {
        TransactionTemplate template = requiresNewTemplate();
        try {
            startTogether.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        try {
            template.executeWithoutResult(tx -> webhookEventRepository.saveAndFlush(
                    PaymentWebhookEvent.create(provider, eventId, "payment_intent.succeeded",
                            "{\"id\":\"" + eventId + "\"}", null, "pi_external", 0L)));
            return true;
        } catch (DataIntegrityViolationException lost) {
            loserFailure.compareAndSet(null, lost);
            return false;
        }
    }
}

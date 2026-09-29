package com.marketplace.payments;

import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R10 (comprehensive-review-ar fix plan §4/R10 — Wave 2): the durable webhook
 * inbox's recovery sweep, end to end on the real module slice (real recorder,
 * real settlement service with its full aspect stack, real PostgreSQL). The
 * plan's regression scenarios, literally:
 * <ul>
 *   <li><b>the money test</b> — "حقن توقف بين التسجيل والتسوية ⇒ إعادة التسليم
 *       عبر مهمة الاسترداد تسوّي الدفعة": the crash between the recorder's
 *       commit and the settlement is simulated by inserting the inbox row
 *       directly (the exact durable state a dead worker leaves behind) and
 *       backdating it past the staleness threshold; the sweep's test seam
 *       then re-delivers it and the payment SETTLES — the state the pre-fix
 *       tombstone lost forever;</li>
 *   <li><b>"الصف SETTLED يمنع الإعادة"</b> — a settled row is never
 *       re-delivered;</li>
 *   <li><b>the permanent-failure family</b> — a re-delivery whose transition
 *       can no longer apply legally lands FAILED with an inspectable reason
 *       (the S12 lesson: no daily-forever resubmission);</li>
 *   <li><b>the charge.refunded replay</b> — the stored refund snapshot drives
 *       the books' sync without the channel being bound;</li>
 *   <li><b>the retention purge</b> — SETTLED rows older than the window are
 *       hard-deleted, FAILED rows are never purged;</li>
 *   <li><b>the freshness guard</b> — a row younger than the staleness
 *       threshold is left alone (its original delivery may still be
 *       mid-dispatch).</li>
 * </ul>
 *
 * <p>Also pins the JSONB payload storage honesty (the V48/V54 official
 * Hibernate JSON mapping): the raw provider payload round-trips byte-identical
 * AND is stored as a JSON object — not a double-encoded JSON string.
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(ModuleTestConfig.class)
class WebhookInboxRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private WebhookInboxRecovery recovery;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentWebhookEventRepository webhookEventRepository;

    @Autowired
    private DataSource dataSource;

    /** The direct SQL channel for the crash-simulation backdate and native checks. */
    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Inserts the inbox row exactly as the recorder's committed transaction
     * would have left it — the durable state after "worker died between the
     * record's REQUIRES_NEW commit and the dispatch".
     */
    private PaymentWebhookEvent strandedRow(String eventType, UUID paymentIntentId,
                                            String externalId, Long refundAmountCents) {
        String eventId = "evt_crash_" + UUID.randomUUID();
        String payload = "{\"id\":\"" + eventId + "\",\"type\":\"" + eventType + "\"}";
        return webhookEventRepository.saveAndFlush(PaymentWebhookEvent.create(
                "stripe", eventId, eventType, payload, paymentIntentId, externalId, refundAmountCents));
    }

    private void backdate(String eventId, String interval) {
        jdbc().update("UPDATE payment_webhook_events SET created_at = now() - interval '"
                + interval + "' WHERE event_id = ?", eventId);
    }

    @Test
    void crashBetweenRecordAndSettle_recoverySweepSettlesThePayment() {
        // The intent is mid-flight (PROCESSING) with its payment row — the
        // state the lost settlement was supposed to transition.
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), 5000L));

        PaymentWebhookEvent row = strandedRow("payment_intent.succeeded", intent.getId(), "pi_ext_1", null);
        backdate(row.getEventId(), "5 minutes");

        // The sweep — driven through its test seam (the @Scheduled cadence is
        // the same code path with Instant.now()).
        recovery.recoverDue(Instant.now());

        // The money settled: the exact transition the pre-fix tombstone lost.
        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .as("the re-delivered payment_intent.succeeded settled the intent")
                .isEqualTo(PaymentIntentStatus.SUCCEEDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .as("the payment row completed with the settlement")
                .isEqualTo(PaymentStatus.COMPLETED);
        // The inbox row closed atomically with the settlement transaction.
        PaymentWebhookEvent settled = webhookEventRepository
                .findByProviderAndEventId("stripe", row.getEventId()).orElseThrow();
        assertThat(settled.getProcessingState())
                .as("the row settled inside the settlement's own transaction")
                .isEqualTo(WebhookProcessingState.SETTLED);
        // JSONB honesty: the raw payload round-trips byte-identical and is
        // stored as a JSON object, not a double-encoded string.
        assertThat(settled.getPayload()).isEqualTo(row.getPayload());
        String stored = jdbc().queryForObject(
                "SELECT payload::text FROM payment_webhook_events WHERE event_id = ?",
                String.class, row.getEventId());
        assertThat(stored).startsWith("{");
    }

    @Test
    void settledRowsAreNeverReDelivered() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);

        PaymentWebhookEvent row = strandedRow("payment_intent.succeeded", intent.getId(), "pi_ext_2", null);
        row.markSettled();
        webhookEventRepository.save(row);
        backdate(row.getEventId(), "5 minutes");

        recovery.recoverDue(Instant.now());

        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .as("a SETTLED row is inert history — its dispatch already happened")
                .isEqualTo(PaymentIntentStatus.PROCESSING);
    }

    @Test
    void reDeliveryThatCanNeverApplyLegallyLandsFailedWithReason() {
        // The intent is already terminal (settled by another path — e.g. the
        // admin command) while the crashed delivery's row sat RECEIVED: the
        // event's transition can no longer apply legally.
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent.markSucceeded();
        intent = paymentIntentRepository.save(intent);

        PaymentWebhookEvent row = strandedRow("payment_intent.succeeded", intent.getId(), "pi_ext_3", null);
        backdate(row.getEventId(), "5 minutes");

        recovery.recoverDue(Instant.now());

        PaymentWebhookEvent failed = webhookEventRepository
                .findByProviderAndEventId("stripe", row.getEventId()).orElseThrow();
        assertThat(failed.getProcessingState())
                .as("a permanently-unappliable re-delivery is FAILED, not retried forever")
                .isEqualTo(WebhookProcessingState.FAILED);
        assertThat(failed.getFailureReason())
                .as("the FAILED row carries its inspectable reason (plan R10 point 5)")
                .contains("Cannot transition");
        // The terminal verdict is idempotent across sweeps: a second sweep
        // re-reads the row and leaves FAILED alone (no re-delivery, no flip).
        recovery.recoverDue(Instant.now());
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", row.getEventId())
                .orElseThrow().getProcessingState()).isEqualTo(WebhookProcessingState.FAILED);
    }

    @Test
    void chargeRefundedReDeliveryReplaysTheStoredSnapshot() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent.markSucceeded();
        intent = paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), 5000L));
        payment.markCompleted("ch_replay");
        payment = paymentRepository.save(payment);

        // A charge.refunded row stranded with its snapshot: the sweep must
        // replay it WITHOUT the channel being bound.
        PaymentWebhookEvent row = strandedRow("charge.refunded", intent.getId(), "pi_ext_4", 300L);
        backdate(row.getEventId(), "5 minutes");

        recovery.recoverDue(Instant.now());

        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .as("the stored snapshot synced the books to the remote cumulative")
                .isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getRefundedAmountCents())
                .isEqualTo(300L);
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", row.getEventId())
                .orElseThrow().getProcessingState()).isEqualTo(WebhookProcessingState.SETTLED);
    }

    @Test
    void purgeRemovesOnlySettledRowsOlderThanTheRetention() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));

        PaymentWebhookEvent settledOld = strandedRow("payment_intent.processing", intent.getId(), null, null);
        settledOld.markSettled();
        webhookEventRepository.save(settledOld);
        backdate(settledOld.getEventId(), "8 days");

        PaymentWebhookEvent failedOld = strandedRow("payment_intent.processing", intent.getId(), null, null);
        failedOld.markFailed("permanently unappliable");
        webhookEventRepository.save(failedOld);
        backdate(failedOld.getEventId(), "8 days");

        int purged = recovery.purgeSettledBefore(Instant.now().minus(WebhookInboxRecovery.SETTLED_RETENTION));

        assertThat(purged).as("the over-retention SETTLED row was hard-deleted").isEqualTo(1);
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", settledOld.getEventId()))
                .as("purged means gone — the dedup memory expired past any provider retry horizon")
                .isEmpty();
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", failedOld.getEventId()))
                .as("FAILED rows are the operator signal — never purged")
                .isPresent();
    }

    @Test
    void freshReceivedRowsAreLeftAlone() {
        PaymentIntent intent = paymentIntentRepository.save(
                PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);

        // Recorded NOW: its original delivery may still be mid-dispatch —
        // the sweep must not race it.
        PaymentWebhookEvent row = strandedRow("payment_intent.succeeded", intent.getId(), "pi_ext_6", null);

        recovery.recoverDue(Instant.now());

        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .as("a row younger than the staleness threshold is not re-delivered")
                .isEqualTo(PaymentIntentStatus.PROCESSING);
        assertThat(webhookEventRepository.findByProviderAndEventId("stripe", row.getEventId())
                .orElseThrow().getProcessingState()).isEqualTo(WebhookProcessingState.RECEIVED);
    }
}

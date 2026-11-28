package com.marketplace.payments;

import com.marketplace.shared.api.OrderRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A4 (adversarial review 2026-11-10, owner-gate batch 6b — the direct B2
 * fix): the refund overload the transactional carriers call must own its
 * retry transaction. The pre-fix shape joined the carrier's REQUIRED
 * transaction while carrying {@code @Retry}: a transient channel failure
 * on the first attempt marked the SHARED carrier transaction
 * rollback-only, the successful retry joined the poisoned transaction,
 * and the carrier's commit failed {@code UnexpectedRollbackException}
 * AFTER the remote refund executed (the completing return rolled back
 * with its money already returned — the ledger's own finding).
 *
 * <p>This test replays EXACTLY that scenario on real PostgreSQL: a
 * transactional carrier (the same REQUIRED carrier
 * {@code OrderReturnsService.complete} runs) calls the return's refund
 * port while the channel throws one transient
 * {@code PspChannelException} then succeeds. The carrier must COMMIT —
 * the partial refund lands (1500 of 3000), the intent answers
 * PARTIALLY_REFUNDED, and no rollback poison surfaces. Against the
 * un-fixed REQUIRED shape this test fails with
 * {@code UnexpectedRollbackException} at the carrier's commit — the
 * regression guard the ledger's row demanded.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import(RefundCarrierPoisoningIntegrationTest.FailingOnceChannelConfig.class)
class RefundCarrierPoisoningIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches EventPublicationArchiveIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    /**
     * The failure-injection channel (the WebhookSettlementRetry
     * precedent's own shape): {@code @Primary} so every injection point
     * sees it — the FIRST remote-refund call throws the transient
     * provider failure, every later call answers the cumulative actual
     * the local books must reflect (the partial 1500 this test's refund
     * requests explicitly). Remote-intent creation is never called (the
     * paid intent is planted directly, the real journey's process step
     * stays the PSP suite's own subject).
     */
    /** The failing-once channel's refund-call counter (round 13's assertion hook). */
    static volatile AtomicInteger REFUND_CALLS;
    static volatile AtomicReference<Long> LIVE_REFUND_TOTAL;

    @TestConfiguration
    static class FailingOnceChannelConfig {

        @Bean
        @Primary
        PspChannel failingOnceChannel() {
            AtomicInteger refundCalls = new AtomicInteger();
            AtomicReference<Long> liveRefundTotal = new AtomicReference<>(1500L);
            // Round 13: the channel's own call counter, exposed to the test
            // so the poisoning replay asserts the channel was actually
            // consulted TWICE — first attempt failed transiently, retry
            // succeeded — instead of proving only the books' outcome (which
            // a NO_CHANNEL bypass could also satisfy).
            REFUND_CALLS = refundCalls;
            LIVE_REFUND_TOTAL = liveRefundTotal;
            return new PspChannel() {
                @Override
                public RemoteIntent createRemoteIntent(UUID marketplaceIntentId, long amountCents,
                                                       String currency, String idempotencyKey) {
                    return new RemoteIntent("pi_poison_" + marketplaceIntentId,
                            "cs_poison_" + marketplaceIntentId);
                }

                @Override
                public RemoteRefund createRemoteRefund(String pspIntentId, Long amountCents,
                                                        String idempotencyKey) {
                    if (refundCalls.incrementAndGet() == 1) {
                        throw new PspChannelException(
                                "transient provider failure (the A4 replay's own first attempt)");
                    }
                    return new RemoteRefund("re_poison_" + pspIntentId, "succeeded", amountCents);
                }

                @Override
                public VerifiedWebhook verifyWebhook(String rawPayload, String signatureHeader) {
                    throw new UnsupportedOperationException(
                            "the A4 replay never verifies provider signatures");
                }

                @Override
                public Long readRemoteRefundTotalCents(String pspIntentId) {
                    // The provider has executed the 1500-cent refund and must
                    // expose that charge-backed cumulative to ground the
                    // journal's reconciled answer (a null read is NOT evidence).
                    return liveRefundTotal.get();
                }
            };
        }
    }

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private OrderRefundPort orderRefundPort;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    // refundPayment carries @PreAuthorize("hasRole('ADMIN')") (PaymentsService:850)
    // — the same documented mock-role pattern PaymentsServiceSecurityTest applies
    // to reach the guarded service method from an integration replay.
    @WithMockUser(roles = "ADMIN")
    void transientChannelFailureDoesNotPoisonTheCarrierCompletion() {
        TransactionTemplate carrier = new TransactionTemplate(transactionManager);
        UUID orderId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();

        // The FK chain payment_intents needs: one consumer user row.
        // users.subject is the unique key (the established seeding
        // precedent: PaymentIntentOneActiveAttemptMigrationIntegrationTest).
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, role) VALUES (?, ?, 'CONSUMER')",
                consumerId, "consumer-" + consumerId);

        // Plant the paid order intent OUTSIDE the carrier (the completing
        // return's money edge finds it exactly as the real journey does),
        // WITH the remote link the ONE refund contract rides (a linked
        // intent routes the refund through the channel — the A4 subject).
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, consumerId, 3000L, "SAR",
                "order-poison-" + orderId, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_poison_" + orderId);
        PaymentIntent plantedIntent = carrier.execute(status -> paymentIntentRepository.save(intent));
        Payment payment = Payment.create(plantedIntent.getId(), 3000L);
        payment.markCompleted("ext-poison-" + plantedIntent.getId());
        Payment plantedPayment = carrier.execute(status -> paymentRepository.save(payment));

        // The carrier — the same REQUIRED carrier OrderReturnsService.complete
        // runs — calls the return's refund port while the channel fails once.
        RefundOutcome outcome = carrier.execute(status ->
                orderRefundPort.refundForOrder(orderId, returnId, 1500L));

        // The carrier COMMITTED (no UnexpectedRollbackException surfaced):
        // the partial refund landed at 1500 of 3000 and the intent answers
        // the partial state — the remote refund executed once, the books
        // hold its number.
        assertThat(outcome.refundedAmountCents()).isEqualTo(1500L);
        assertThat(paymentRepository.findById(plantedPayment.getId()).orElseThrow()
                .getRefundedAmountCents()).isEqualTo(1500L);
        assertThat(paymentIntentRepository.findById(plantedIntent.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentIntentStatus.PARTIALLY_REFUNDED);

        // CodeRabbit round 13: the books' numbers alone could also pass with
        // the refund routed around the channel (NO_CHANNEL) — the counter
        // proves the carrier's refund overload actually consulted the
        // channel exactly twice: the first attempt failed transiently and
        // the retry succeeded (the A4 replay's own shape).
        assertThat(REFUND_CALLS.get()).isEqualTo(2);

        // A repeat can only be reconciled by provider evidence. Even though
        // the local books already show 1500, a missing live cumulative must
        // not let independent/local arithmetic stand in for this operation.
        LIVE_REFUND_TOTAL.set(null);
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                carrier.execute(status -> orderRefundPort.refundForOrder(orderId, returnId, 1500L)))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("has not reached the required amount yet");
        assertThat(REFUND_CALLS.get())
                .as("re-reading an EXECUTED operation never re-submits the remote refund")
                .isEqualTo(2);
    }
}

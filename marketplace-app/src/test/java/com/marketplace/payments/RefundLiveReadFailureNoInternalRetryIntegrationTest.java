package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.LedgerReadPort;
import com.marketplace.shared.api.ServiceUnavailableException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;
import test.config.ModuleTestConfig;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CodeRabbit round 15 on #828 (2026-11-19T14:40Z review, minor —
 * stability): the live-grounding read's deliberate 503 is the CALLER's
 * retry vehicle, never an internal {@code @Retry(paymentProcessing)} loop.
 * Under the round-14 fix alone, the {@code ServiceUnavailableException}
 * translated from the failed live read was NOT in the retry instance's
 * ignore list — Resilience4j re-ran the whole {@code refundPayment} up to
 * three times, re-invoking the live read on every attempt while HOLDING
 * the {@code refundCarriers} bulkhead permit and the carrier transaction
 * open: during a provider outage the shared semaphore saturates and every
 * other refund caller answers 503 from OUR loop, not from the provider.
 *
 * <p>The fix: {@code ServiceUnavailableException} joins the retry
 * instance's ignore-exceptions (the books-sync wait's
 * {@code ConflictException} precedent, verbatim — the application.yml
 * block documents the law). This test pins it THROUGH THE REAL ASPECT
 * STACK (the module slice boots the actual {@code @Retry} proxy with the
 * production retry configuration): a dispute re-completion whose
 * arithmetic alone passes, an EXECUTED operation, and a channel whose
 * live read ALWAYS fails — the 503 must surface after EXACTLY ONE live
 * read, with no remote refund ever created.</p>
 */
@ApplicationModuleTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@Import({ModuleTestConfig.class,
        RefundLiveReadFailureNoInternalRetryIntegrationTest.FailingLiveReadChannelConfig.class})
@WithMockUser(roles = "ADMIN")
class RefundLiveReadFailureNoInternalRetryIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** How many live reads reached the channel (asserted: exactly one). */
    static final AtomicInteger liveReads = new AtomicInteger();

    /** How many remote refunds the channel was asked to create (asserted: zero). */
    static final AtomicInteger remoteRefundCreations = new AtomicInteger();

    /**
     * The channel whose live cumulative read ALWAYS fails — the provider
     * outage shape. Every other method answers inertly: the test never
     * reaches them (the gate refuses before any provider write).
     */
    @TestConfiguration
    static class FailingLiveReadChannelConfig {
        @Bean
        PspChannel testFailingLiveReadChannel() {
            return new PspChannel() {
                @Override
                public RemoteIntent createRemoteIntent(UUID marketplaceIntentId, long amountCents,
                        String currency, String idempotencyKey) {
                    throw new UnsupportedOperationException("not under test");
                }

                @Override
                public RemoteRefund createRemoteRefund(String pspIntentId, Long amountCents,
                        String idempotencyKey) {
                    remoteRefundCreations.incrementAndGet();
                    throw new UnsupportedOperationException("never reached: the gate refuses first");
                }

                @Override
                public VerifiedWebhook verifyWebhook(String rawPayload, String signatureHeader) {
                    throw new UnsupportedOperationException("not under test");
                }

                @Override
                public Long readRemoteRefundTotalCents(String pspIntentId) {
                    liveReads.incrementAndGet();
                    throw new PspChannelException(
                            "simulated provider outage on the live cumulative read (intent " + pspIntentId + ")");
                }
            };
        }
    }

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    // W11 (the matrix's G5): SettlementEligibilityService boots inside this
    // slice and consumes the ledger's settlement-eligibility read port —
    // the adapter lives in the ledger module, outside the slice.
    @MockitoBean
    LedgerReadPort ledgerReadPort;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentRefundOperationRepository paymentRefundOperationRepository;

    @Test
    void theLiveGroundingReads503ReachesTheCallerAfterExactlyOneRead_NoInternalRetry() {
        // the books' arithmetic alone passes: 3000 >= 0 + 3000 — the gate
        // must stand on the LIVE read, and the read always fails
        PaymentIntent intent = paymentIntentRepository.save(PaymentIntent.create(
                UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null));
        intent.markProcessing();
        intent = paymentIntentRepository.save(intent);
        intent.assignPspIntentId("pi_live_read_outage");
        intent = paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), 5000L));
        payment.markCompleted("ch_live_read_outage");
        payment.markPartiallyRefunded(3000L);
        paymentRepository.save(payment);
// The provider can only refund a captured (SUCCEEDED) payment intent.
        intent.markSucceeded();
        intent.markPartiallyRefunded(3000L);
        paymentIntentRepository.save(intent);
        // the operation is EXECUTED and durable: the journal's own record
        UUID disputeId = UUID.randomUUID();
        String operationKey = PaymentsService.StableRefundScope.forDispute(disputeId)
                .idempotencyKey(payment.getId(), 3000L);
        PaymentRefundOperation executed = new PaymentRefundOperation(
                operationKey, payment.getId(), 3000L, 0L);
        executed.markExecuted("re_live_read_prior");
        paymentRefundOperationRepository.save(executed);

        liveReads.set(0);
        remoteRefundCreations.set(0);

        // through the REAL proxied chain: @Retry(paymentProcessing) +
        // @Transactional(REQUIRES_NEW) + the bulkhead — the aspects boot
        // from the production configuration
        assertThatThrownBy(() -> paymentsService.refundPayment(payment.getId(), 3000L,
                PaymentsService.StableRefundScope.forDispute(disputeId)))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("EXECUTED and durable")
                .hasMessageContaining("retry the resolution");

        // the 503 reached the caller after EXACTLY ONE live read — the
        // internal @Retry loop never re-ran the method (the ignore list
        // carries the 503, the ConflictException precedent verbatim)
        assertThat(liveReads.get())
                .as("the deliberate 503 is the CALLER's retry vehicle — exactly one live read")
                .isEqualTo(1);

        // and nothing re-submitted: no remote refund was ever created
        assertThat(remoteRefundCreations.get())
                .as("the gate refuses before any provider write")
                .isZero();

        // fresh database read: the EXECUTED record stays durable (the
        // journal's own transaction survived the carrier's rollback)
        assertThat(paymentRefundOperationRepository.findById(operationKey))
                .as("the EXECUTED record survives the failed re-completion")
                .isPresent()
                .hasValueSatisfying(op -> {
                    assertThat(op.getStatus()).isEqualTo("EXECUTED");
                    assertThat(op.getRemoteRefundId()).isEqualTo("re_live_read_prior");
                });
    }
}

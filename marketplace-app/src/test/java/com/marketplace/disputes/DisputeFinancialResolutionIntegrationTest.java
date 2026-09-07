package com.marketplace.disputes;

import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.LedgerEntryType;
import com.marketplace.ledger.LedgerService;
import com.marketplace.ledger.ProviderBalance;
import com.marketplace.payments.Payment;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentIntentStatus;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentStatus;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L24 (feature-expansion roadmap §5, Week 2) — the dispute financial
 * resolution loop over the REAL schema and the REAL modules:
 * confirm lands the credit through the real event listener, the
 * REFUND_CONSUMER decision invokes the existing refund path through the
 * real {@code PaymentRefundAdapter} port, and the refund's own
 * {@code PaymentStateChangedEvent} drives the ledger debit that mirrors
 * the original credit.
 *
 * <p>Acceptance criteria (§5-L24): (1) a refund decision executes the
 * refund exactly once — the repeated request is a 409 BEFORE any refund
 * attempt and nothing moves a second time; (2) the provider's balance
 * reflects the decision — the REFUND_DEBIT mirrors the PAYMENT_CREDIT
 * (same booking priceCents, opposite sign); (3) every decision leaves an
 * Envers trace on the @Audited dispute row — the revisions carry the
 * resolution and the refund linkage.
 *
 * <p>Schema honesty: the {@code AuditedWritesIntegrationTest} pattern —
 * Flyway enabled and {@code ddl-auto=none} against a dedicated container,
 * so V38 (the disputes + disputes_aud columns) is the schema the loop runs
 * on, not an entity-generated one. The booking seam is the standard
 * {@code @MockitoBean} boundary (the L21 convention) so the price and
 * provider ownership are controlled while every money path in between is
 * production code.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DisputeFinancialResolutionIntegrationTest {

    private static final long PRICE_CENTS = 5000L;
    private static final long COMMISSION_CENTS = 500L; // rate 0.10 (test profile)

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches AuditedWritesIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @MockitoBean
    BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private DisputeService disputeService;

    @Autowired
    private DisputeRepository disputeRepository;

    @Autowired
    private PaymentsService paymentsService;

    @Autowired
    private PaymentIntentRepository paymentIntentRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Test
    @WithMockUser(roles = "ADMIN")
    void refundConsumerResolution_closesTheMoneyLoopOnce() {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", PRICE_CENTS, "SAR",
                Instant.now(), Instant.now()));

        // The paid precondition, planted through the real factories: intent
        // CREATED -> PROCESSING, then the REAL confirm path (markSucceeded +
        // payment COMPLETED + the COMPLETED event).
        PaymentIntent intent = paymentIntentRepository.save(PaymentIntent.create(
                bookingId, consumerId, PRICE_CENTS, "SAR", "l24-" + UUID.randomUUID()));
        intent.markProcessing();
        paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), PRICE_CENTS));
        Dispute dispute = disputeRepository.save(Dispute.open(bookingId, consumerId, "l24 damage"));

        paymentsService.confirmIntent(intent.getId(), "evt_l24_confirm");

        // The COMPLETED event is async AFTER_COMMIT — the credit lands the
        // balance at price - commission.
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS);

        // The decision: REFUND_CONSUMER through the real module port.
        Dispute resolved = disputeService.resolve(dispute.getId(), DisputeResolution.REFUND_CONSUMER,
                SecurityContextHolder.getContext().getAuthentication());

        // The refund's REFUNDED event drives the debit that mirrors the credit.
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS);

        // Acceptance 1 — idempotent: the repeated decision is a 409 BEFORE
        // any refund attempt, and nothing moves a second time.
        assertThatThrownBy(() -> disputeService.resolve(dispute.getId(),
                DisputeResolution.REFUND_CONSUMER,
                SecurityContextHolder.getContext().getAuthentication()))
                .isInstanceOf(ConflictException.class);
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS - PRICE_CENTS);

        // The movement is recorded on the dispute: the linkage and the
        // cumulative refunded total.
        assertThat(resolved.getStatus()).isEqualTo(DisputeStatus.RESOLVED);
        assertThat(resolved.getResolution()).isEqualTo(DisputeResolution.REFUND_CONSUMER);
        assertThat(resolved.getRefundPaymentId()).isEqualTo(payment.getId());
        assertThat(resolved.getRefundedAmountCents()).isEqualTo(PRICE_CENTS);

        // The payment moved exactly once to REFUNDED.
        Payment refundedPayment = paymentRepository.findById(payment.getId()).orElseThrow();
        assertThat(refundedPayment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refundedPayment.getRefundedAmountCents()).isEqualTo(PRICE_CENTS);
        assertThat(paymentIntentRepository.findById(intent.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentIntentStatus.REFUNDED);

        // Acceptance 2 — the ledger reflects the decision: one REFUND_DEBIT
        // whose amount is the credit's mirror, keyed by the derived
        // refund-<intentId> source id (the replay-proof belt).
        UUID refundSourceId = UUID.nameUUIDFromBytes(("refund-" + intent.getId()).getBytes());
        LedgerEntry refundDebit = ledgerEntryRepository.findBySourceId(refundSourceId).orElseThrow();
        assertThat(refundDebit.getEntryType()).isEqualTo(LedgerEntryType.REFUND_DEBIT);
        assertThat(refundDebit.getAmountCents()).isEqualTo(PRICE_CENTS);
        UUID creditSourceId = intent.getId();
        LedgerEntry credit = ledgerEntryRepository.findBySourceId(creditSourceId).orElseThrow();
        assertThat(credit.getEntryType()).isEqualTo(LedgerEntryType.PAYMENT_CREDIT);
        assertThat(credit.getAmountCents()).isEqualTo(PRICE_CENTS);

        // Acceptance 3 — the Envers trace: every decision leaves revisions
        // on the dispute; the latest revision carries the resolution and
        // the refund linkage (V38 columns live in disputes_aud).
        var revisions = disputeRepository.findRevisions(dispute.getId(), Pageable.unpaged());
        assertThat(revisions.getContent()).hasSize(2);
        Dispute latest = revisions.getContent().get(revisions.getContent().size() - 1).getEntity();
        assertThat(latest.getResolution()).isEqualTo(DisputeResolution.REFUND_CONSUMER);
        assertThat(latest.getRefundPaymentId()).isEqualTo(payment.getId());
        assertThat(latest.getRefundedAmountCents()).isEqualTo(PRICE_CENTS);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void releaseProviderResolution_leavesTheMoneyWhereItIs() {
        UUID providerId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerId, consumerId, "CONFIRMED", PRICE_CENTS, "SAR",
                Instant.now(), Instant.now()));

        PaymentIntent intent = paymentIntentRepository.save(PaymentIntent.create(
                bookingId, consumerId, PRICE_CENTS, "SAR", "l24-rel-" + UUID.randomUUID()));
        intent.markProcessing();
        paymentIntentRepository.save(intent);
        Payment payment = paymentRepository.save(Payment.create(intent.getId(), PRICE_CENTS));
        Dispute dispute = disputeRepository.save(Dispute.open(bookingId, consumerId, "l24 release"));

        paymentsService.confirmIntent(intent.getId(), "evt_l24_release");
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS);

        Dispute resolved = disputeService.resolve(dispute.getId(), DisputeResolution.RELEASE_PROVIDER,
                SecurityContextHolder.getContext().getAuthentication());

        // The provider keeps the money: no refund movement, no debit, the
        // linkage columns stay empty and the balance is untouched.
        assertThat(resolved.getResolution()).isEqualTo(DisputeResolution.RELEASE_PROVIDER);
        assertThat(resolved.getRefundPaymentId()).isNull();
        assertThat(resolved.getRefundedAmountCents()).isNull();
        awaitBalance(providerId, PRICE_CENTS - COMMISSION_CENTS);
        assertThat(paymentRepository.findById(payment.getId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
        UUID refundSourceId = UUID.nameUUIDFromBytes(("refund-" + intent.getId()).getBytes());
        assertThat(ledgerEntryRepository.findBySourceId(refundSourceId)).isEmpty();

        // The decision still leaves its Envers trace.
        var revisions = disputeRepository.findRevisions(dispute.getId(), Pageable.unpaged());
        assertThat(revisions.getContent()).hasSize(2);
        Dispute latest = revisions.getContent().get(revisions.getContent().size() - 1).getEntity();
        assertThat(latest.getResolution()).isEqualTo(DisputeResolution.RELEASE_PROVIDER);
        assertThat(latest.getRefundPaymentId()).isNull();
    }

    /** Plain poll loop (30s / 200ms) — no Awaitility dependency in this reactor. */
    private void awaitBalance(UUID providerId, long expectedCents) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        Long last = null;
        while (System.nanoTime() < deadline) {
            last = ledgerService.getBalance(providerId).getAvailableCents();
            if (last != null && last == expectedCents) {
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
                "Provider %s balance never reached %s after 30s (last seen: %s) —"
                        + " the async payment-state listener did not land the ledger movement.",
                providerId, expectedCents, last));
    }
}

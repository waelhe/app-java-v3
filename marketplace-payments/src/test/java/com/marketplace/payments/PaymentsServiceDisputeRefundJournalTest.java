package com.marketplace.payments;

import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review round 1 (the payments trio — the dispute refund's frozen
 * operation): the durable journal's three guards, exercised through the
 * service's own refund path.
 *
 * <ul>
 *   <li><b>The amount freeze</b> — a re-approval carrying a DIFFERENT
 *       amount is rejected BEFORE any provider call: the operation's
 *       amount-less key would otherwise ride different params under the
 *       same key, which the provider's idempotency contract rejects
 *       outright, leaving the retry forever unable to complete.</li>
 *   <li><b>The executed-match</b> — after the rollback-then-webhook window
 *       (the remote refund executed; the resolve transaction rolled
 *       back), the retry returns the reconciled state: no cap re-application
 *       (the very cap that would reject the executed refund as an
 *       over-refund), no re-submission (the double-refund window after the
 *       provider's 24-hour idempotency-record expiry). Review round 2: the
 *       ONLY execution proof is the operation's own EXECUTED record (the
 *       provider's success answer for THIS operation, with the remote
 *       refund's identity) — never the books' cumulative arithmetic, which
 *       an INDEPENDENT refund of the same or a larger amount satisfies
 *       just as well while this operation never sent.</li>
 *   <li><b>The first submission</b> — the journal row is born with the
 *       amount and the books baseline, and the provider's success answer
 *       records the remote refund's identity (EXECUTED) — the durable
 *       local identity that does not expire.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentsServiceDisputeRefundJournalTest {

    @Mock
    private PaymentIntentRepository intentRepository;
    @Mock
    private PaymentRepository paymentRepository;
    @Mock
    private PaymentWebhookEventRepository webhookEventRepository;
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private BookingParticipantProvider bookingParticipantProvider;
    @Mock
    private PaymentWebhookSecurity webhookSecurity;
    @Mock
    private PaymentRefundOperationRepository journalRepository;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PspChannel> boundChannel = mock(ObjectProvider.class);
    @Mock
    private PspChannel pspChannel;

    private PaymentsService service() {
        // The journal is built HERE, never in a field initializer: Mockito
        // injects @Mock fields only after field initializers ran, so a
        // field-built journal captured a null repository (CI round 2,
        // measured 2026-10-28 — the NPE at ensureSubmitted line 45).
        WebhookEventRecorder recorder = new WebhookEventRecorder(webhookEventRepository);
        return new PaymentsService(
                intentRepository, paymentRepository, webhookEventRepository, eventPublisher,
                currentUserProvider, bookingParticipantProvider, webhookSecurity, recorder,
                new PaymentIntentSettlementService(intentRepository, paymentRepository, eventPublisher, recorder),
                new SettlementEligibilityService(mock(com.marketplace.shared.api.LedgerReadPort.class),
                        intentRepository, bookingParticipantProvider),
                boundChannel, new CommissionProperties(java.math.BigDecimal.valueOf(0.10)),
                new PaymentRefundOperationJournal(journalRepository));
    }

    /** A collected payment of 5000 with the given cumulative refunded total. */
    private Payment collectedPayment(long refundedSoFar) {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 5000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_dispute");
        Payment payment = Payment.create(intent.getId(), 5000L);
        payment.markCompleted("ch_dispute");
        if (refundedSoFar > 0) {
            payment.markPartiallyRefunded(refundedSoFar);
            intent.markPartiallyRefunded(refundedSoFar);
        }
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        return payment;
    }

    private static String disputeKey(UUID paymentId, UUID disputeId) {
        return "marketplace-refund-" + paymentId + "-dispute-" + disputeId;
    }

    @Test
    void aDifferentAmountIsRejectedBeforeTheProviderCall_TheOperationFrozeAtFirstSubmission() {
        UUID disputeId = UUID.randomUUID();
        Payment payment = collectedPayment(0L);
        // the journal already froze the FIRST submission's amount (3000)
        PaymentRefundOperation frozen =
                new PaymentRefundOperation(disputeKey(payment.getId(), disputeId), payment.getId(), 3000L, 0L);
        when(journalRepository.findById(disputeKey(payment.getId(), disputeId)))
                .thenReturn(Optional.of(frozen));

        // the re-approval carries 3500 — a different decision must be
        // rejected explicitly, never re-ridden under the same key
        assertThatThrownBy(() -> service().refundPayment(payment.getId(), 3500L,
                PaymentsService.StableRefundScope.forDispute(disputeId)))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("froze its amount")
                .hasMessageContaining("3000");

        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
    }

    @Test
    void theExecutedOperationReturnsTheReconciledState_NoCapReApplication_NoResubmission() {
        UUID disputeId = UUID.randomUUID();
        // the rollback-then-webhook window: the refund executed remotely
        // (the journal's EXECUTED record is the proof — review round 2),
        // the charge.refunded webhook synced the books to 3000, the dispute
        // is still OPEN — the pre-fix cap check (3000 + 3000 > 5000) would
        // reject the very refund that already executed
        Payment payment = collectedPayment(3000L);
        PaymentRefundOperation executed =
                new PaymentRefundOperation(disputeKey(payment.getId(), disputeId), payment.getId(), 3000L, 0L);
        executed.markExecuted("re_dispute_prior");
        when(journalRepository.findById(disputeKey(payment.getId(), disputeId)))
                .thenReturn(Optional.of(executed));

        PaymentsService.RefundedPayment refunded = service().refundPayment(payment.getId(), 3000L,
                PaymentsService.StableRefundScope.forDispute(disputeId));

        // the reconciled current state answers; nothing is re-submitted
        assertThat(refunded.payment().getRefundedAmountCents()).isEqualTo(3000L);
        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
        // and the journal is not re-marked either — the operation is already
        // answered by its EXECUTED record
        verify(journalRepository, never()).save(any(PaymentRefundOperation.class));
    }

    @Test
    void aSubmittedOperationIsNotExecutedByTheBooksAlone() {
        // Review round 2, the executed-proof finding: a SUBMITTED operation
        // (its submission never reached a provider success) whose books
        // baseline was passed by an INDEPENDENT refund of the same amount
        // must NOT be treated as executed — the round-1 arithmetic would
        // return a successful outcome here and close the dispute without
        // its refund ever executing. The honest answer is the loud cap
        // conflict: the frozen 3000 against books already at 3000 cannot
        // be re-applied, and no re-submission happens.
        UUID disputeId = UUID.randomUUID();
        Payment payment = collectedPayment(3000L);
        PaymentRefundOperation submitted =
                new PaymentRefundOperation(disputeKey(payment.getId(), disputeId), payment.getId(), 3000L, 0L);
        when(journalRepository.findById(disputeKey(payment.getId(), disputeId)))
                .thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service().refundPayment(payment.getId(), 3000L,
                PaymentsService.StableRefundScope.forDispute(disputeId)))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("exceeds");
        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
    }

    @Test
    void anOverCapFirstRequestFreezesNothing_TheCorrectedAmountStillCan() {
        // Review round 2, the freeze-order finding: the round-1 order froze
        // whatever the FIRST request carried — including an over-cap amount
        // — in its own REQUIRES_NEW transaction before the cap check
        // rejected it; the admin's corrected amount then hit the frozen-
        // amount rejection and the dispute could never refund. The freeze
        // now lands only on the first VALID submission.
        UUID disputeId = UUID.randomUUID();
        Payment payment = collectedPayment(0L);
        String key = disputeKey(payment.getId(), disputeId);
        java.util.Map<String, PaymentRefundOperation> store = new java.util.HashMap<>();
        when(journalRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(journalRepository.save(any(PaymentRefundOperation.class)))
                .thenAnswer(inv -> {
                    PaymentRefundOperation operation = inv.getArgument(0);
                    store.put(operation.getIdempotencyKey(), operation);
                    return operation;
                });

        // the over-cap first submission: rejected by the cap, and NOTHING
        // is journaled — the record stays clean for the corrected decision
        assertThatThrownBy(() -> service().refundPayment(payment.getId(), 6000L,
                PaymentsService.StableRefundScope.forDispute(disputeId)))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("exceeds");
        assertThat(store).as("the over-cap request froze nothing").isEmpty();

        // the corrected amount proceeds on the SAME dispute: the first
        // VALID submission freezes, the provider answers, the operation is
        // recorded EXECUTED with the remote identity
        when(pspChannel.createRemoteRefund(eq("pi_remote_dispute"), eq(3000L), eq(key)))
                .thenReturn(new PspChannel.RemoteRefund("re_dispute_fix", "succeeded", 3000L));
        PaymentsService.RefundedPayment refunded = service().refundPayment(payment.getId(), 3000L,
                PaymentsService.StableRefundScope.forDispute(disputeId));
        assertThat(refunded.payment().getRefundedAmountCents()).isEqualTo(3000L);
        PaymentRefundOperation operation = store.get(key);
        assertThat(operation).isNotNull();
        assertThat(operation.getAmountCents()).isEqualTo(3000L);
        assertThat(operation.getStatus()).isEqualTo("EXECUTED");
        assertThat(operation.getRemoteRefundId()).isEqualTo("re_dispute_fix");
    }

    @Test
    void theFirstSubmissionIsJournaledAndTheProviderAnswerRecordsTheRemoteIdentity() {
        UUID disputeId = UUID.randomUUID();
        Payment payment = collectedPayment(0L);
        String key = disputeKey(payment.getId(), disputeId);
        // a store-backed repository: the journal's own find-or-create and
        // its EXECUTED marking ride the SAME instance, exactly as the
        // managed-entity dirty-check flush would in production
        java.util.Map<String, PaymentRefundOperation> store = new java.util.HashMap<>();
        when(journalRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(journalRepository.save(any(PaymentRefundOperation.class)))
                .thenAnswer(inv -> {
                    PaymentRefundOperation operation = inv.getArgument(0);
                    store.put(operation.getIdempotencyKey(), operation);
                    return operation;
                });
        when(pspChannel.createRemoteRefund(eq("pi_remote_dispute"), eq(3000L), eq(key)))
                .thenReturn(new PspChannel.RemoteRefund("re_dispute_1", "succeeded", 3000L));

        PaymentsService.RefundedPayment refunded = service().refundPayment(payment.getId(), 3000L,
                PaymentsService.StableRefundScope.forDispute(disputeId));

        // the provider call carried the dispute-scoped key with the amount
        verify(pspChannel).createRemoteRefund(eq("pi_remote_dispute"), eq(3000L), eq(key));
        assertThat(refunded.payment().getRefundedAmountCents()).isEqualTo(3000L);

        // the journal: born at submission (amount + books baseline)…
        PaymentRefundOperation operation = store.get(key);
        assertThat(operation).isNotNull();
        assertThat(operation.getAmountCents()).isEqualTo(3000L);
        assertThat(operation.getBooksAtSubmitCents()).isEqualTo(0L);
        // …and marked EXECUTED with the remote refund's identity
        assertThat(operation.getStatus()).describedAs("the provider answer marks EXECUTED")
                .isEqualTo("EXECUTED");
        assertThat(operation.getRemoteRefundId()).isEqualTo("re_dispute_1");
    }
}

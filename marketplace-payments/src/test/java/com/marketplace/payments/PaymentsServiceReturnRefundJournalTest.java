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
 * CodeRabbit round 1 on #828 (major — data integrity): the RETURN scope's
 * executed-proof journey. The refund rides THIS method's REQUIRES_NEW
 * transaction, so it survives the carrier's (OrderReturnsService.complete)
 * rollback — but the pre-fix re-completion hit the common cap checks FIRST:
 * the books already carried the executed refund (2000 of a 3000 payment),
 * the re-completion carried the return's own frozen amount (2000), and
 * 2000 + 2000 &gt; 3000 rejected the very refund that executed — the return
 * could NEVER complete (stuck RECEIVED forever, its money already gone).
 *
 * <p>Pinned laws (the dispute twin's discipline, PaymentsServiceDisputeRefundJournalTest's
 * own shape):
 * <ul>
 *   <li><b>The executed-match</b> — the carrier rolled back after the refund
 *       executed; the re-completion with the SAME scope returns the
 *       reconciled state: no cap re-application (the cap that would reject
 *       the executed refund as an over-refund), no re-submission (the
 *       double-refund window).</li>
 *   <li><b>The books-sync gate</b> — an EXECUTED record whose books have NOT
 *       reached the submission baseline plus the frozen amount answers the
 *       explicit wait (the charge.refunded webhook still pending).</li>
 *   <li><b>The first submission</b> — the journal row is born with the
 *       return's amount and the books baseline, and the provider's success
 *       answer records the remote refund's identity (EXECUTED).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentsServiceReturnRefundJournalTest {

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
        // The journal is built HERE, never in a field initializer (the
        // dispute journal test's own measured CI lesson).
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

    /** A collected payment of 3000 with the given cumulative refunded total. */
    private Payment collectedPayment(long refundedSoFar) {
        PaymentIntent intent = PaymentIntent.create(UUID.randomUUID(), UUID.randomUUID(), 3000L, null, null, null);
        intent.markProcessing();
        intent.markSucceeded();
        intent.assignPspIntentId("pi_remote_return");
        Payment payment = Payment.create(intent.getId(), 3000L);
        payment.markCompleted("ch_return");
        if (refundedSoFar > 0) {
            payment.markPartiallyRefunded(refundedSoFar);
            intent.markPartiallyRefunded(refundedSoFar);
        }
        when(intentRepository.findById(intent.getId())).thenReturn(Optional.of(intent));
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(boundChannel.getIfAvailable()).thenReturn(pspChannel);
        return payment;
    }

    private PaymentRefundOperation executedOperation(UUID paymentId, long amountCents, long booksAtSubmit) {
        PaymentRefundOperation operation =
                new PaymentRefundOperation("key", paymentId, amountCents, booksAtSubmit);
        operation.markExecuted("re_executed_return");
        return operation;
    }

    @Test
    void theCarrierRolledBackAfterTheRefundExecuted_theRecompletionReturnsTheReconciledState() {
        // The measured defect: the return's 2000 refund of a 3000 payment
        // executed in its REQUIRES_NEW transaction; the carrier (the return
        // completion) rolled back AFTER it — the books advanced (2000) but
        // the return stays RECEIVED. The pre-fix re-completion hit the cap
        // (2000 + 2000 > 3000) BEFORE the RETURN key's replay and rejected
        // the very refund that executed. The fix reads the journal's
        // EXECUTED record FIRST and answers the reconciled state.
        PaymentsService svc = service();
        Payment payment = collectedPayment(2000L); // the books already carry the executed refund
        UUID returnId = UUID.randomUUID();
        String operationKey = PaymentsService.StableRefundScope.forReturn(returnId)
                .idempotencyKey(payment.getId(), 2000L);
        when(journalRepository.findById(operationKey))
                .thenReturn(Optional.of(executedOperation(payment.getId(), 2000L, 0L)));

        PaymentsService.RefundedPayment result =
                svc.refundPayment(payment.getId(), 2000L, PaymentsService.StableRefundScope.forReturn(returnId));

        assertThat(result).isNotNull();
        // no re-submission: the executed refund is never sent again
        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
    }

    @Test
    void anExecutedRecordAheadOfTheBooksAnswersTheExplicitWebhookWait() {
        // The books-sync gate (the dispute twin's c62bdab9 round): the
        // EXECUTED record with the books still at the pre-refund baseline
        // answers the explicit wait — never a reconciled answer carrying
        // numbers the provider already moved past.
        PaymentsService svc = service();
        Payment payment = collectedPayment(0L); // the webhook has not synced the books yet
        UUID returnId = UUID.randomUUID();
        String operationKey = PaymentsService.StableRefundScope.forReturn(returnId)
                .idempotencyKey(payment.getId(), 2000L);
        when(journalRepository.findById(operationKey))
                .thenReturn(Optional.of(executedOperation(payment.getId(), 2000L, 0L)));

        assertThatThrownBy(() ->
                svc.refundPayment(payment.getId(), 2000L, PaymentsService.StableRefundScope.forReturn(returnId)))
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("await the charge.refunded sync");
        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
    }

    @Test
    void theFirstSubmissionFreezesTheRecordAndTheProviderAnswerMarksItExecuted() {
        // The first VALID submission: the journal row is born with the
        // return's amount and the books baseline (in the journal's own
        // REQUIRES_NEW transaction), the provider's success answer records
        // the remote refund's identity, and the books advance by the remote
        // cumulative actual. CodeRabbit round 5: the repository mock is a
        // STORE — findById returns what save stored — so markExecuted's
        // transition actually runs and the captured record's final state is
        // asserted, not just the call count.
        PaymentsService svc = service();
        Payment payment = collectedPayment(0L);
        UUID returnId = UUID.randomUUID();
        String operationKey = PaymentsService.StableRefundScope.forReturn(returnId)
                .idempotencyKey(payment.getId(), 2000L);
        java.util.concurrent.atomic.AtomicReference<PaymentRefundOperation> stored =
                new java.util.concurrent.atomic.AtomicReference<>();
        when(journalRepository.findById(operationKey))
                .thenAnswer(inv -> Optional.ofNullable(stored.get()));
        when(journalRepository.save(any(PaymentRefundOperation.class)))
                .thenAnswer(inv -> {
                    stored.set(inv.getArgument(0));
                    return inv.getArgument(0);
                });
        when(pspChannel.createRemoteRefund(eq("pi_remote_return"), eq(2000L), eq(operationKey)))
                .thenReturn(new PspChannel.RemoteRefund("re_new_return", "succeeded", 2000L));

        PaymentsService.RefundedPayment result =
                svc.refundPayment(payment.getId(), 2000L, PaymentsService.StableRefundScope.forReturn(returnId));

        assertThat(result).isNotNull();
        // the EXECUTED marking: the provider's success answer for THIS
        // return's refund is recorded (the durable identity that does not
        // expire) — the re-completion after a carrier rollback replays it.
        org.mockito.ArgumentCaptor<PaymentRefundOperation> captor =
                org.mockito.ArgumentCaptor.forClass(PaymentRefundOperation.class);
        verify(journalRepository).save(captor.capture());
        PaymentRefundOperation recorded = captor.getValue();
        // the store-backed read let markExecuted's transition RUN: the
        // captured record ends EXECUTED with the provider's refund identity
        org.assertj.core.api.Assertions.assertThat(recorded.getStatus()).isEqualTo("EXECUTED");
        org.assertj.core.api.Assertions.assertThat(recorded.getRemoteRefundId()).isEqualTo("re_new_return");
        // the three reads of the first-submission journey: findSubmitted
        // (the executed-proof lookup), ensureSubmitted (the find-or-create),
        // and markExecuted (the provider answer's durable recording).
        verify(journalRepository, org.mockito.Mockito.times(3)).findById(operationKey);
    }

    /**
     * CodeRabbit round 12 (the live-read refresh under the executed answer):
     * the books' cumulative arithmetic ALONE passed (an INDEPENDENT event
     * saturated it — 1000 of the 3000 charge) while THIS operation's own
     * settle's live read had failed (the round-10 path: markExecuted committed
     * in REQUIRES_NEW, the carrier rolled back, the inbox row deleted). The
     * re-completion arriving NOW must not answer the reconciled state on the
     * arithmetic alone: the live remote cumulative (1500 — the independent
     * refund PLUS this operation's money) is fetched and applied to the books
     * BEFORE the answer, and the reconciled state stands on books that
     * provably contain the operation's own money. No re-submission, no
     * re-marking.
     */
    @Test
    void anIndependentEventSaturatingTheArithmetic_refreshesTheBooksThroughTheLiveReadBeforeTheAnswer() {
        PaymentsService svc = service();
        // the books carry an INDEPENDENT refund (1000) that saturated the
        // gate's arithmetic: 1000 >= 0 + 500 for this operation's frozen 500
        Payment payment = collectedPayment(1000L);
        when(paymentRepository.findByPaymentIntentId(payment.getPaymentIntentId()))
                .thenReturn(Optional.of(payment));
        UUID returnId = UUID.randomUUID();
        String operationKey = PaymentsService.StableRefundScope.forReturn(returnId)
                .idempotencyKey(payment.getId(), 500L);
        // the operation EXECUTED (its settle's journal commit survived the
        // read failure and the carrier rollback); its own money (500) is at
        // the provider but NOT provably in the books
        when(journalRepository.findById(operationKey))
                .thenReturn(Optional.of(executedOperation(payment.getId(), 500L, 0L)));
        // the live remote cumulative: the independent 1000 PLUS this
        // operation's 500 — the provider's own charge total right now
        when(pspChannel.readRemoteRefundTotalCents("pi_remote_return")).thenReturn(1500L);

        PaymentsService.RefundedPayment result =
                svc.refundPayment(payment.getId(), 500L, PaymentsService.StableRefundScope.forReturn(returnId));

        // the reconciled answer stands — no re-submission, no re-marking
        assertThat(result).isNotNull();
        verify(pspChannel, never()).createRemoteRefund(anyString(), any(), anyString());
        // and the books now carry the operation's own money: the live read
        // (1500) was fetched and applied before the answer
        verify(pspChannel).readRemoteRefundTotalCents("pi_remote_return");
        assertThat(payment.getRefundedAmountCents()).isEqualTo(1500L);
        assertThat(payment.getStatus()).isEqualTo(com.marketplace.payments.PaymentStatus.PARTIALLY_REFUNDED);
    }
}

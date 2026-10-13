package com.marketplace.lending;

import com.marketplace.shared.api.DisputeOpenedEvent;
import com.marketplace.shared.api.DisputeResolvedEvent;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.DisputeSubject;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Stage 8 (ADR-0004) + ADR-0009 — the two module listeners' dispatch law:
 * the payment's COMPLETED event marks the paid gate (anything else is a
 * no-op), and the dispute events freeze/resume/terminate ONLY their LOAN
 * subjects (a BOOKING dispute never touches the lending machine).
 */
class LendingListenersTest {

    private final LendingService lendingService = mock(LendingService.class);
    private final LendingPaymentListener paymentListener = new LendingPaymentListener(lendingService);
    private final LoanDisputeListener disputeListener = new LoanDisputeListener(lendingService);

    @Test
    void thePaymentCompletionMarksThePaidGate() {
        UUID intentId = UUID.randomUUID();
        paymentListener.onPaymentStateChanged(new PaymentStateChangedEvent(intentId, "COMPLETED"));
        verify(lendingService).markPaidFromPayment(intentId);
    }

    @Test
    void anyOtherPaymentStateIsANoOp() {
        paymentListener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "PROCESSING"));
        paymentListener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "FAILED"));
        paymentListener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "REFUNDED"));
        verifyNoInteractions(lendingService);
    }

    @Test
    void aLoanSubjectDisputeFreezesTheLoan() {
        UUID loanId = UUID.randomUUID();
        disputeListener.onDisputeOpened(new DisputeOpenedEvent(
                UUID.randomUUID(), DisputeSubject.LOAN, null, loanId, UUID.randomUUID()));
        verify(lendingService).markDisputedFromDispute(loanId);
    }

    @Test
    void aBookingSubjectDisputeNeverTouchesTheLendingMachine() {
        disputeListener.onDisputeOpened(new DisputeOpenedEvent(
                UUID.randomUUID(), DisputeSubject.BOOKING, UUID.randomUUID(), null, UUID.randomUUID()));
        disputeListener.onDisputeResolved(new DisputeResolvedEvent(
                UUID.randomUUID(), DisputeSubject.BOOKING, UUID.randomUUID(), null, UUID.randomUUID(),
                DisputeResolution.REFUND_CONSUMER, 5000L));
        verifyNoInteractions(lendingService);
    }

    @Test
    void aLoanSubjectResolutionRidesThroughTyped() {
        UUID loanId = UUID.randomUUID();
        disputeListener.onDisputeResolved(new DisputeResolvedEvent(
                UUID.randomUUID(), DisputeSubject.LOAN, null, loanId, UUID.randomUUID(),
                DisputeResolution.REFUND_CONSUMER, null));
        verify(lendingService).resolveDisputeFromDisputes(loanId, DisputeResolution.REFUND_CONSUMER);
    }
}

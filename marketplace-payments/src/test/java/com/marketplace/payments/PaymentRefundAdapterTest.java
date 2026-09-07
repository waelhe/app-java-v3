package com.marketplace.payments;

import com.marketplace.payments.spi.PaymentRefundAdapter;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * L24 (roadmap §5): the dispute-driven refund port implementation — the
 * booking-keyed lookup into the payment pair and the delegation into the
 * production refund path, plus the rollback-then-webhook reconciliation
 * guard (CodeRabbit round 1, verified real).
 */
@ExtendWith(MockitoExtension.class)
class PaymentRefundAdapterTest {

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentsService paymentsService;

    @InjectMocks
    private PaymentRefundAdapter adapter;

    @Test
    void refundForBooking_delegatesToTheProductionRefundPath() {
        UUID bookingId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(intentId, bookingId, UUID.randomUUID(), 5000L, null);
        Payment payment = new Payment(paymentId, intentId, 5000L);
        Payment refunded = new Payment(paymentId, intentId, 5000L);
        refunded.markCompleted("evt_adapter");
        refunded.markRefunded();
        when(paymentIntentRepository.findByBookingId(bookingId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(payment));
        when(paymentsService.refundPayment(paymentId, null)).thenReturn(refunded);

        RefundOutcome outcome = adapter.refundForBooking(bookingId, null);

        assertEquals(paymentId, outcome.paymentId());
        assertEquals(5000L, outcome.refundedAmountCents());
        verify(paymentsService).refundPayment(paymentId, null);
    }

    @Test
    void refundForBooking_alreadyRefundedPayment_returnsTheExecutedOutcomeWithoutRefundingAgain() {
        // CodeRabbit round 1: the dispute's full-refund decision meeting an
        // already-REFUNDED payment (remote refund succeeded inside a rolled
        // back resolve transaction, then the charge.refunded webhook synced
        // the books) is ALREADY satisfied — the executed outcome lets the
        // dispute record the linkage and resolve instead of 409ing forever
        // on the payment state machine.
        UUID bookingId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(intentId, bookingId, UUID.randomUUID(), 5000L, null);
        Payment refunded = new Payment(paymentId, intentId, 5000L);
        refunded.markCompleted("evt_adapter");
        refunded.markRefunded();
        when(paymentIntentRepository.findByBookingId(bookingId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(refunded));

        RefundOutcome outcome = adapter.refundForBooking(bookingId, null);

        assertEquals(paymentId, outcome.paymentId());
        assertEquals(5000L, outcome.refundedAmountCents());
        verifyNoInteractions(paymentsService);
    }

    @Test
    void refundForBooking_partiallyRefundedPayment_stillDelegates() {
        // A partial refund does NOT satisfy the dispute's full-refund
        // decision — the delegation completes the remainder (the payment
        // state machine allows PARTIALLY_REFUNDED -> REFUNDED).
        UUID bookingId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(intentId, bookingId, UUID.randomUUID(), 5000L, null);
        Payment partial = new Payment(paymentId, intentId, 5000L);
        partial.markCompleted("evt_adapter");
        partial.markPartiallyRefunded(2000L);
        Payment full = new Payment(paymentId, intentId, 5000L);
        full.markCompleted("evt_adapter");
        full.markPartiallyRefunded(2000L);
        full.markRefunded();
        when(paymentIntentRepository.findByBookingId(bookingId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.of(partial));
        when(paymentsService.refundPayment(paymentId, null)).thenReturn(full);

        RefundOutcome outcome = adapter.refundForBooking(bookingId, null);

        assertEquals(5000L, outcome.refundedAmountCents());
        verify(paymentsService).refundPayment(paymentId, null);
    }

    @Test
    void refundForBooking_missingIntent_throws() {
        UUID bookingId = UUID.randomUUID();
        when(paymentIntentRepository.findByBookingId(bookingId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> adapter.refundForBooking(bookingId, null));
        verifyNoInteractions(paymentsService);
    }

    @Test
    void refundForBooking_missingPayment_throws() {
        UUID bookingId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        PaymentIntent intent = new PaymentIntent(intentId, bookingId, UUID.randomUUID(), 5000L, null);
        when(paymentIntentRepository.findByBookingId(bookingId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intentId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> adapter.refundForBooking(bookingId, null));
        verifyNoInteractions(paymentsService);
    }
}

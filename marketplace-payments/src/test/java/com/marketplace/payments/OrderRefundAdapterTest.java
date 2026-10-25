package com.marketplace.payments;

import com.marketplace.payments.spi.OrderRefundAdapter;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The V230 return-scoped refund port implementation (the RMA lifecycle's
 * payments half): the order-keyed lookup into the payment pair and the
 * delegation into the ONE production refund path with the return-stable
 * idempotency scope — the PaymentRefundAdapter dispute twin's measured
 * pattern, pinned here for the payments module's coverage contract (the
 * branch's Tests-modules red: instructions 0.69 vs the 0.70 minimum).
 */
@ExtendWith(MockitoExtension.class)
class OrderRefundAdapterTest {

    @Mock
    private PaymentIntentRepository paymentIntentRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentsService paymentsService;

    @InjectMocks
    private OrderRefundAdapter adapter;

    @Test
    void refundForOrder_delegatesWithTheReturnStableScope() {
        UUID orderId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        UUID returnId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, consumerId,
                5000L, null, null, null);
        Payment payment = Payment.create(intent.getId(), 5000L);
        Payment refunded = Payment.create(intent.getId(), 5000L);
        refunded.markCompleted("evt_rma");
        refunded.markPartiallyRefunded(1500L);
        when(paymentIntentRepository.findByOrderId(orderId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.of(payment));
        when(paymentsService.refundPayment(payment.getId(), 1500L, returnId))
                .thenReturn(new PaymentsService.RefundedPayment(refunded, intent));

        RefundOutcome outcome = adapter.refundForOrder(orderId, returnId, 1500L);

        // the OUTCOME rides the refunded payment the production path returned
        assertEquals(refunded.getId(), outcome.paymentId());
        assertEquals(1500L, outcome.refundedAmountCents());
        // the return's id rides as the PSP idempotency scope — one return,
        // one remote refund, replayed safely on a retried completion; the
        // delegation itself rides the payment the lookup found
        verify(paymentsService).refundPayment(payment.getId(), 1500L, returnId);
    }

    @Test
    void refundForOrder_orderWithoutIntent_throwsTheHonest404() {
        UUID orderId = UUID.randomUUID();
        when(paymentIntentRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adapter.refundForOrder(orderId, UUID.randomUUID(), 1500L));

        assertTrue(ex.getMessage().contains("no payment intent"));
        verifyNoInteractions(paymentsService);
    }

    @Test
    void refundForOrder_intentWithoutPayment_throwsTheHonest404() {
        UUID orderId = UUID.randomUUID();
        UUID consumerId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, consumerId,
                5000L, null, null, null);
        when(paymentIntentRepository.findByOrderId(orderId)).thenReturn(Optional.of(intent));
        when(paymentRepository.findByPaymentIntentId(intent.getId())).thenReturn(Optional.empty());

        ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                () -> adapter.refundForOrder(orderId, UUID.randomUUID(), 1500L));

        assertTrue(ex.getMessage().contains("no collected payment"));
        verifyNoInteractions(paymentsService);
    }
}

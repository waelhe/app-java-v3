package com.marketplace.payments.spi;

import com.marketplace.payments.Payment;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.OrderRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The V225 RMA lifecycle's payments half: the orders module's return
 * completion refunds the buyer through the ONE production refund path
 * ({@code PaymentsService.refundPayment}) via the {@link OrderRefundPort}
 * contract — the {@code PaymentRefundAdapter} dispute twin verbatim, keyed
 * by the order's ONE intent (the V184 order_id UNIQUE constraint makes the
 * single lookup the whole truth).
 *
 * <p>Everything L19 put in the refund path rides along: the ADMIN
 * method-security gate (the return completion that reaches here is already
 * ADMIN-guarded — defense in depth), the PSP idempotency key, the
 * remote-cumulative-actual sync, and the {@code PaymentStateChangedEvent}
 * the ledger listens to. PARTIALLY_REFUNDED stays the payments side's own
 * bookkeeping — the ledger's deliberate no-op (the ADR-0013 D3 record).
 */
@Component
public class OrderRefundAdapter implements OrderRefundPort {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentsService paymentsService;

    public OrderRefundAdapter(PaymentIntentRepository paymentIntentRepository,
                              PaymentRepository paymentRepository,
                              PaymentsService paymentsService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.paymentsService = paymentsService;
    }

    @Override
    public RefundOutcome refundForOrder(UUID orderId, UUID returnId, long amountMinor) {
        var intent = paymentIntentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no payment intent to refund"));
        Payment payment = paymentRepository.findByPaymentIntentId(intent.getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Order " + orderId + " has no collected payment to refund"));
        PaymentsService.RefundedPayment refunded =
                paymentsService.refundPayment(payment.getId(), amountMinor,
                PaymentsService.StableRefundScope.forReturn(returnId));
        return new RefundOutcome(refunded.payment().getId(),
                refunded.payment().getRefundedAmountCents());
    }
}

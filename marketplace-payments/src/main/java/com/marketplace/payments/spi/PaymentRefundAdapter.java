package com.marketplace.payments.spi;

import com.marketplace.payments.Payment;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.PaymentRefundPort;
import com.marketplace.shared.api.RefundOutcome;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * L24 (feature-expansion roadmap §5): the payments module's implementation
 * of the {@link PaymentRefundPort} cross-module contract. The dispute layer
 * resolves a {@code REFUND_CONSUMER} decision by invoking the EXISTING
 * refund path here — keyed by the booking both sides already share, no
 * payment internals leak across the module boundary.
 *
 * <p>The execution stays the one production refund path
 * ({@code PaymentsService.refundPayment}) with everything L19 put in it:
 * the ADMIN method-security gate (the dispute resolve that reaches here is
 * already ADMIN-guarded — defense in depth), the PSP idempotency key, the
 * remote-cumulative-actual sync and the {@code PaymentStateChangedEvent}
 * the ledger listens to.
 */
@Component
public class PaymentRefundAdapter implements PaymentRefundPort {

    private final PaymentIntentRepository paymentIntentRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentsService paymentsService;

    public PaymentRefundAdapter(PaymentIntentRepository paymentIntentRepository,
                                PaymentRepository paymentRepository,
                                PaymentsService paymentsService) {
        this.paymentIntentRepository = paymentIntentRepository;
        this.paymentRepository = paymentRepository;
        this.paymentsService = paymentsService;
    }

    @Override
    public RefundOutcome refundForBooking(UUID bookingId, Long amountCents) {
        PaymentIntent intent = paymentIntentRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new ResourceNotFoundException("No payment intent for booking: " + bookingId));
        Payment payment = paymentRepository.findByPaymentIntentId(intent.getId())
                .orElseThrow(() -> new ResourceNotFoundException("No payment for booking: " + bookingId));
        Payment refunded = paymentsService.refundPayment(payment.getId(), amountCents);
        return new RefundOutcome(refunded.getId(), refunded.getRefundedAmountCents());
    }
}

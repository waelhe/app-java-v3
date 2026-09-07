package com.marketplace.payments.spi;

import com.marketplace.payments.Payment;
import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentIntentRepository;
import com.marketplace.payments.PaymentRepository;
import com.marketplace.payments.PaymentStatus;
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
        // CodeRabbit round 1 (verified real): the rollback-then-webhook
        // window. A remote refund can succeed at the PSP inside a resolve
        // transaction that then rolls back (commit failure); the
        // charge.refunded webhook syncs the local books afterwards — the
        // money side fully reconciles (payment REFUNDED + the ledger
        // debit), but the dispute row is still OPEN, and a retry would
        // 409 forever on the payment state machine (REFUNDED -> REFUNDED
        // is not a transition). When the dispute's full-refund decision
        // meets an already-REFUNDED payment, the decision is ALREADY
        // satisfied: return the executed outcome so the dispute records
        // the linkage and resolves. Without the webhook's sync the retry
        // stays self-healing by design — the same derived idempotency
        // key returns the same remote refund.
        if (amountCents == null && payment.getStatus() == PaymentStatus.REFUNDED) {
            return new RefundOutcome(payment.getId(), payment.getRefundedAmountCents());
        }
        Payment refunded = paymentsService.refundPayment(payment.getId(), amountCents);
        return new RefundOutcome(refunded.getId(), refunded.getRefundedAmountCents());
    }
}

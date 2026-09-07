package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port for the dispute-driven refund (L24 — feature-expansion roadmap §5):
 * {@code marketplace-disputes} invokes the EXISTING refund path inside
 * {@code marketplace-payments} through this module contract (no internal
 * import), keyed by the booking both sides already share.
 *
 * <p>The amount mirrors the existing refund path's full/partial capability
 * ({@code refundPayment(paymentId, amountCents)}): {@code null} refunds the
 * payment in full — the dispute decision's shape.
 */
public interface PaymentRefundPort {

    /**
     * Refunds the payment of the booking's payment intent.
     *
     * @param bookingId   the disputed booking
     * @param amountCents optional partial amount; {@code null} = full refund
     * @return the refund outcome — the refunded payment and its cumulative
     *         refunded total after the execution
     * @throws ResourceNotFoundException when the booking has no payment
     *                                   intent or no payment row
     */
    RefundOutcome refundForBooking(UUID bookingId, Long amountCents);
}

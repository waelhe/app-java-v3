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
 * payment in full — the dispute decision's shape. The dispute id is the
 * refund operation's STABLE scope (agent findings 2026-10-26, row 35):
 * one dispute is one remote refund — the approved amount freezes at first
 * execution and every rollback-then-retry reconciles against the operation
 * itself (the PSP replays the executed refund under the same key instead of
 * paying the same partial refund twice under a state-drifted one).
 */
public interface PaymentRefundPort {

    /**
     * Refunds the payment of the booking's payment intent.
     *
     * @param bookingId   the disputed booking
     * @param amountCents optional partial amount; {@code null} = full refund
     * @param disputeId   the refund operation's stable scope — the dispute
     *                    being resolved
     * @return the refund outcome — the refunded payment and its cumulative
     *         refunded total after the execution
     * @throws ResourceNotFoundException when the booking has no payment
     *                                   intent or no payment row
     */
    RefundOutcome refundForBooking(UUID bookingId, Long amountCents, UUID disputeId);
}

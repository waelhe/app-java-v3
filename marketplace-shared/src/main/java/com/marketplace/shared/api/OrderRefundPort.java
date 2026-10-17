package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The V225 RMA lifecycle's money half: the orders module's return
 * completion refunds the buyer through the ONE refund contract inside
 * {@code marketplace-payments} via this module contract (the
 * {@code PaymentRefundPort} dispute twin, keyed by the ORDER both sides
 * already share — no payment internals leak across the boundary).
 *
 * <p>The amount is the return's FROZEN share (computed and frozen at
 * APPROVAL); {@code null} is deliberately absent — a return is partial by
 * construction (the full-order refund stays the cancellation's own path).
 */
public interface OrderRefundPort {

    /**
     * Refunds the payment of the order's payment intent by the frozen
     * partial amount.
     *
     * @param orderId     the order whose return completed
     * @param amountMinor the frozen partial refund amount (positive)
     * @return the refund outcome — the refunded payment and its cumulative
     *         refunded total after the execution
     * @throws ResourceNotFoundException when the order has no payment
     *                                   intent or no payment row
     */
    RefundOutcome refundForOrder(UUID orderId, long amountMinor);
}

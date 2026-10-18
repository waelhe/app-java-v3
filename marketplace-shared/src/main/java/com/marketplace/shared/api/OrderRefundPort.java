package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The V230 RMA lifecycle's money half: the orders module's return
 * completion refunds the buyer through the ONE refund contract inside
 * {@code marketplace-payments} via this module contract (the
 * {@code PaymentRefundPort} dispute twin, keyed by the ORDER both sides
 * already share — no payment internals leak across the boundary).
 *
 * <p>The amount is the return's FROZEN share (computed and frozen at
 * APPROVAL); {@code null} is deliberately absent — a return is partial by
 * construction (the full-order refund stays the cancellation's own path).
 *
 * <p>The refund is scoped by the RETURN's id (the review's Major finding):
 * the PSP idempotency key derives from the return — a retried completion
 * of the SAME return re-derives the same key and replays the same remote
 * refund instead of double-charging, even when a sibling return's refund
 * advanced the payment's cumulative refunded total in between (the
 * state-derived key would have drifted across that advance).
 */
public interface OrderRefundPort {

    /**
     * Refunds the payment of the order's payment intent by the frozen
     * partial amount, scoped to the completing return.
     *
     * @param orderId     the order whose return completed
     * @param returnId    the completing return — the PSP idempotency key's
     *                    stable scope
     * @param amountMinor the frozen partial refund amount (positive)
     * @return the refund outcome — the refunded payment and its cumulative
     *         refunded total after the execution
     * @throws ResourceNotFoundException when the order has no payment
     *                                   intent or no payment row
     */
    RefundOutcome refundForOrder(UUID orderId, UUID returnId, long amountMinor);
}

package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The refund execution outcome returned to the dispute layer (L24): the
 * refunded payment row and its cumulative refunded total after the run.
 */
public record RefundOutcome(
        UUID paymentId,
        long refundedAmountCents
) {
}

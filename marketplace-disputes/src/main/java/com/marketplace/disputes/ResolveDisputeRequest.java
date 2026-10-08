package com.marketplace.disputes;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * L24 (feature-expansion roadmap §5): the admin's resolve request — the
 * decision that carries the financial outcome. House {@code @RequestBody}
 * record pattern ({@code ReplyRequest}, L21).
 *
 * <p>B-06 (compliance plan 0.7): the optional {@code refundAmountCents}
 * ACTIVATES the partial refund on the existing payments path
 * ({@code PaymentRefundPort.refundForBooking(bookingId, amountCents)} —
 * the port has carried the capability since L24; the dispute decision
 * simply never used it). {@code null} keeps the full-refund decision
 * shape; a value is honored only on {@code REFUND_CONSUMER} (a 400
 * otherwise — money never moves implicitly).
 */
public record ResolveDisputeRequest(
        @Schema(description = "The resolve decision", example = "REFUND_CONSUMER")
        @NotNull DisputeResolution resolution,

        @Schema(description = "Optional partial refund amount in cents — activates the "
                + "partial refund on the existing payments path; null = full refund "
                + "(the L24 shape). Honored on REFUND_CONSUMER decisions only.",
                example = "2500")
        @Positive Long refundAmountCents
) {
}

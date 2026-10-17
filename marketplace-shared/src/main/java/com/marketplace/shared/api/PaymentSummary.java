package com.marketplace.shared.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The payment-intent summary the money surfaces carry. Widened by the
 * payments plan's Phase-1 unified finance-operator read model: the
 * ORIGIN and its subject reference (the "investigate any origin by a
 * single search" acceptance journey — the pre-widening summary named only
 * the booking subject, so an ORDER/LOAN/AD row answered an incomplete
 * picture), the ADR-0013 frozen commission rate (D2: the rate frozen at
 * intent creation; {@code null} only for pre-V220 legacy rows whose
 * creation-time rate is unknowable), and the PSP linkage (the
 * provider-side evidence key for reconciliation).
 */
public record PaymentSummary(
        UUID id,
        UUID bookingId,
        UUID adCampaignId,
        UUID orderId,
        UUID loanId,
        UUID consumerId,
        Long amountCents,
        String currency,
        String origin,
        String status,
        Long refundedAmountCents,
        BigDecimal commissionRate,
        String pspIntentId,
        Instant createdAt,
        Instant updatedAt
) {
}

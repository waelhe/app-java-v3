package com.marketplace.disputes;

import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.DisputeSubject;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR-0009: the wire gains the subject facts additively (subjectType +
 * loanId) — the booking subjects keep their existing shape untouched.
 */
public record DisputeResponse(
        UUID id,
        DisputeSubject subjectType,
        UUID bookingId,
        UUID loanId,
        UUID openedBy,
        DisputeStatus status,
        DisputeResolution resolution,
        UUID refundPaymentId,
        Long refundedAmountCents,
        String reason,
        Instant createdAt,
        Instant updatedAt
) {
}

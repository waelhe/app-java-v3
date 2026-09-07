package com.marketplace.disputes;

import java.time.Instant;
import java.util.UUID;

public record DisputeResponse(
        UUID id,
        UUID bookingId,
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

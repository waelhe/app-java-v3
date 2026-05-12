package com.marketplace.shared.api;

import java.util.UUID;

public record PaymentIntentDetails(
        UUID paymentIntentId,
        UUID bookingId,
        UUID consumerId,
        String status
) {
}

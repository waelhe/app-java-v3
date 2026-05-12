package com.marketplace.shared.api;

import java.util.UUID;

public record DisputeResolvedEvent(UUID disputeId, UUID bookingId, boolean refundRecommended) {
}

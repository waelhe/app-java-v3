package com.marketplace.delivery;
import java.time.Instant;
import java.util.UUID;
public record DeliveryCourierResponse(UUID userId, boolean available, Double latitude, Double longitude,
        Instant locationUpdatedAt, UUID currentDeliveryRequestId, Instant lastAssignedAt) {
    static DeliveryCourierResponse from(DeliveryCourier c) {
        return new DeliveryCourierResponse(c.getUserId(), c.isAvailable(), c.getLatitude(), c.getLongitude(),
                c.getLocationUpdatedAt(), c.getCurrentDeliveryRequestId(), c.getLastAssignedAt());
    }
}
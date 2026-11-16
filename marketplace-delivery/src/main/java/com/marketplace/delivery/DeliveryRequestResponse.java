package com.marketplace.delivery;

import java.time.Instant;
import java.util.UUID;

public record DeliveryRequestResponse(
        UUID id,
        UUID quoteId,
        UUID orderId,
        UUID paymentIntentId,
        DeliveryRequestStatus status,
        long amountMinor,
        long courierCompensationMinor,
        String currency,
        long distanceMeters,
        long estimatedDurationSeconds,
        String pickupAddress,
        String dropoffAddress,
        String packageDescription,
        UUID courierUserId,
        String failureReason,
        Instant deliveredAt,
        Instant createdAt,
        Instant updatedAt,
        String proofCode) {

    static DeliveryRequestResponse from(DeliveryRequest request, String proof) {
        return new DeliveryRequestResponse(request.getId(), request.getQuoteId(), request.getOrderId(), request.getPaymentIntentId(),
                request.getStatus(), request.getAmountMinor(), request.getCourierCompensationMinor(),
                request.getCurrency(), request.getDistanceMeters(), request.getRouteDurationSeconds(),
                request.getPickupAddress(), request.getDropoffAddress(), request.getPackageDescription(),
                request.getCourierUserId(), request.getFailureReason(), request.getDeliveredAt(),
                request.getCreatedAt(), request.getUpdatedAt(), proof);
    }
}

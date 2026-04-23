package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;

public record BookingInfo(
        UUID providerId,
        UUID consumerId,
        String status,
        Long priceCents,
        String currency,
        Instant createdAt,
        Instant updatedAt
) {

    public BookingInfo {
        if (priceCents == null || priceCents <= 0) {
            throw new IllegalStateException("Booking has invalid price: " + priceCents + " cents");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalStateException("Booking has invalid currency: " + currency);
        }
    }

    public void requireStatus(String required, String operation) {
        if (!required.equals(status)) {
            throw new IllegalStateException("Cannot " + operation + " for booking status " + status + "; required status is " + required);
        }
    }

    public void requireStatusNot(String forbidden, String operation) {
        if (forbidden.equals(status)) {
            throw new IllegalStateException("Cannot " + operation + " for booking status " + status);
        }
    }

    public void requireParticipant(UUID userId) {
        if (!providerId.equals(userId) && !consumerId.equals(userId)) {
            throw new AccessDeniedException("You are not a participant in this booking");
        }
    }
}

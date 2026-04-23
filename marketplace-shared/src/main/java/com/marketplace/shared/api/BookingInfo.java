package com.marketplace.shared.api;

import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.UUID;

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
            throw new IllegalStateException("Cannot " + operation + " when booking status is " + status + ". Required: " + required);
        }
    }

    public void requireStatusNot(String forbidden, String operation) {
        if (forbidden.equals(status)) {
            throw new IllegalStateException("Cannot " + operation + " when booking status is " + forbidden);
        }
    }

    public void requireParticipant(UUID userId) {
        if (!consumerId.equals(userId) && !providerId.equals(userId)) {
            throw new AccessDeniedException("You are not a participant in this booking");
        }
    }
}

package com.marketplace.notifications;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * geographic subscription POST body — one level-3 neighborhood to
 * subscribe to (the explicit geographic act; the service validates
 * existence and level through {@code GeoLookupPort}).
 *
 * @param locationId the level-3 neighborhood to subscribe to
 */
public record NotificationGeoSubscriptionRequest(
        @NotNull UUID locationId) {
}

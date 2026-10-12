package com.marketplace.notifications;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the REST
 * contract of one of the caller's OPT-IN geographic subscriptions — the
 * location and the subscription moment (the row's whole honest state).
 *
 * @param locationId    the subscribed level-3 neighborhood (geo_locations)
 * @param subscribedAt  the subscription moment (the row's created_at)
 */
@Schema(description = "One geographic notification subscription")
public record NotificationGeoSubscriptionView(
        @Schema(description = "The subscribed level-3 neighborhood id") UUID locationId,
        @Schema(description = "When the subscription was created") Instant subscribedAt) {
}

package com.marketplace.notifications;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * effective state of one user×topic×channel switch as the hierarchical
 * topic preferences endpoint returns it — the stored override when one
 * exists, otherwise the enabled default (the L22 view shape, the topic
 * dimension in place of the type).
 *
 * @param topic    the subject family the switch governs
 * @param channel  the delivery channel the switch governs (EMAIL/WS only)
 * @param enabled  whether that channel stays on for that topic
 */
@Schema(description = "One hierarchical topic preference switch")
public record NotificationTopicPreferenceView(
        @Schema(description = "The notification subject family", example = "OFFICIAL")
        com.marketplace.notifications.routing.NotificationTopic topic,
        @Schema(description = "The delivery channel (EMAIL or WS)", example = "EMAIL")
        NotificationChannel channel,
        @Schema(description = "Whether the channel stays enabled for the topic")
        boolean enabled) {
}

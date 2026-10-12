package com.marketplace.notifications;

import com.marketplace.notifications.routing.NotificationTopic;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): one entry
 * of the hierarchical topic preferences PUT body — a single
 * topic×channel switch to set. The {@code NotificationPreferenceUpdate}
 * record's shape with the topic dimension; the binding-layer validation
 * rejects any channel outside the governed pair (the in-app channel is
 * always on — the L22 rule; push awaits the provider decision D-10) —
 * the same three-layer invariant: binding check, service check, schema
 * CHECK (V198).
 *
 * @param topic    the subject family the switch governs
 * @param channel  the delivery channel the switch governs (EMAIL/WS only)
 * @param enabled  the new channel state for that topic
 */
public record NotificationTopicPreferenceUpdate(
        @NotNull NotificationTopic topic,
        @NotNull NotificationChannel channel,
        boolean enabled) {

    /**
     * Rejects a switch on a channel topic preferences do not govern.
     *
     * @return true unless this entry names the always-on in-app channel
     *         or the unborn push channel
     */
    @AssertTrue(message = "Topic preferences govern the EMAIL and WS channels only")
    public boolean isGovernedChannel() {
        return channel == null
                || channel == NotificationChannel.EMAIL
                || channel == NotificationChannel.WS;
    }
}

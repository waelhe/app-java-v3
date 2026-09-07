package com.marketplace.notifications;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

/**
 * L22 (feature-expansion roadmap §5, Week 2): one entry of the preferences
 * PUT body — a single type×channel switch to set. House {@code @RequestBody}
 * record pattern ({@code ResolveDisputeRequest}, L24).
 *
 * <p>Binding-layer validation (CodeRabbit round 1): the in-app (DB) channel
 * is always on — an opt-out is rejected here by the framework
 * ({@code @AssertTrue} on the derived property), a 400 through
 * {@code MethodArgumentNotValidException} before any controller code runs.
 * {@code NotificationPreferenceService} guards the same invariant at the
 * service layer (defense in depth for non-HTTP callers — the house
 * pattern of guarding the service itself), and the V40 schema CHECK makes
 * it absolute against raw SQL. Three layers, one invariant.
 *
 * @param type     the notification type the switch governs
 * @param channel  the delivery channel the switch governs
 * @param enabled  the new channel state for that type
 */
public record NotificationPreferenceUpdate(
        @NotNull NotificationType type,
        @NotNull NotificationChannel channel,
        boolean enabled) {

    /**
     * Rejects an in-app (DB) opt-out: that channel is always created by
     * design, so the API must not accept a request claiming otherwise.
     * Null channel is tolerated here so {@code @NotNull} produces the
     * field-level message.
     *
     * @return true unless this entry opts out of the always-on DB channel
     */
    @AssertTrue(message = "The in-app (DB) channel is always on — an opt-out is not supported")
    public boolean isNotInAppChannelOptOut() {
        return channel == null || channel != NotificationChannel.DB || enabled;
    }
}

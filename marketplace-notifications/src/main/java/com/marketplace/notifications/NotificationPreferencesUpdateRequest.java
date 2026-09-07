package com.marketplace.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * L22 (feature-expansion roadmap §5, Week 2): the preferences PUT body —
 * the set of switches to apply in one request. Upsert semantics: each
 * entry creates the override row if absent or flips the existing one;
 * "back to default" is expressed as {@code enabled = true}, so no delete
 * path exists and the matrix stays sparse. An in-app ({@code DB}) opt-out
 * is rejected — that channel is always on.
 *
 * @param preferences  the switches to apply (at least one)
 */
public record NotificationPreferencesUpdateRequest(
        @NotEmpty @Valid List<@Valid NotificationPreferenceUpdate> preferences) {
}

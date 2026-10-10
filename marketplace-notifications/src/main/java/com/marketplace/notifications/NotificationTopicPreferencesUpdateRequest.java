package com.marketplace.notifications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Phase 7 (execution plan §10 / §8.1 — notification routing): the
 * hierarchical topic preferences PUT body — the set of topic×channel
 * switches to apply in one request. Upsert semantics identical to the
 * L22 type matrix: each entry creates the override row if absent or
 * flips the existing one; "back to default" is {@code enabled = true}.
 *
 * @param preferences  the switches to apply (at least one)
 */
public record NotificationTopicPreferencesUpdateRequest(
        @NotEmpty @Valid List<@Valid NotificationTopicPreferenceUpdate> preferences) {
}

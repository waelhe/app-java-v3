package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * The review-moderation queue's board row — the moderation inbox's
 * actionable head: which reviewer rated which provider, at what rating,
 * saying what, carrying which fraud signals (the queue's own
 * prioritization input). The components are named for the queue board's
 * own template contract verbatim.
 *
 * <p>A shared-api record by the {@code BookingSummary} precedent — the
 * cross-module read shape lives where both sides see it; the reviews
 * module's adapter maps its own {@code ModerationQueueItem} onto it,
 * the consumer never sees the module's types.</p>
 */
public record ReviewModerationQueueEntry(
        UUID id,
        UUID reviewerId,
        UUID providerId,
        Integer rating,
        String comment,
        List<String> flags
) {
}

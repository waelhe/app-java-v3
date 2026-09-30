package com.marketplace.reviews;

import java.time.Instant;
import java.util.UUID;

/**
 * W1 (§4.4/§4.5): the review response. The pre-W1 fields keep their
 * places; W1 adds the origin badge ({@code origin} — «موثّقة» vs «عامة»),
 * the moderation state, the optional listing target, and the
 * reviewer-identity block (the plan's "هوية المراجع في الاستجابة": a
 * display name honouring {@code pseudonymized_at} plus his
 * published-review count) with the helpful-vote count as the third
 * batch-resolved block.
 */
public record ReviewResponse(
        UUID id,
        UUID bookingId,
        Integer rating,
        String comment,
        String reply,
        String direction,
        Instant repliedAt,
        Instant createdAt,
        Instant updatedAt,
        String origin,
        String moderationStatus,
        UUID listingId,
        String reviewerName,
        long reviewerReviewCount,
        long helpfulCount
) {
}


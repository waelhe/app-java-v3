package com.marketplace.reviews;

import java.util.UUID;

/** W1 (§4.4): the grouped per-reviewer published-review count (the batch read's carrier). */
public record ReviewerReviewCount(UUID reviewerId, long count) {
}

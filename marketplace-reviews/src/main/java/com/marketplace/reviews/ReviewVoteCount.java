package com.marketplace.reviews;

import java.util.UUID;

/** W1 (§4.5): the grouped per-review helpful-vote count (the batch read's carrier). */
public record ReviewVoteCount(UUID reviewId, long count) {
}

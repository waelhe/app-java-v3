package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The recomputed rating statistics for one provider (L21). The average is
 * {@code AVG(rating)} over the provider's live reviews; the count rides along
 * so callers can distinguish "no reviews" from a computed value when needed.
 */
public record ReviewStats(
        UUID providerId,
        double averageRating,
        long reviewCount
) {
}

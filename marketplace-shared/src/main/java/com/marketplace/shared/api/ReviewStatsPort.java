package com.marketplace.shared.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Port for cross-module access to review statistics (L21 — feature-expansion
 * roadmap §5): the provider module consumes the existing review events
 * ({@code ReviewCreatedEvent} / {@code ReviewUpdatedEvent}, which carry the
 * review id only) and resolves the reviewed provider plus its recomputed
 * average through this port — the same lookup pattern
 * {@code LedgerPaymentEventListener} applies with
 * {@code PaymentIntentLookupPort}.
 *
 * <p>The average is always recomputed from the reviews table (never
 * incrementally adjusted) so the stored value cannot drift from the source
 * of truth.
 */
public interface ReviewStatsPort {

    /**
     * Resolves the provider the review belongs to and that provider's
     * recomputed average rating. Empty when the review does not exist (or is
     * soft-deleted) — the caller simply skips.
     */
    Optional<ReviewStats> findStatsByReviewId(UUID reviewId);

    /**
     * L21 recompute-inside-the-lock seam: the provider side resolves the
     * reviewed provider first, locks the profile row, then recomputes the
     * aggregate through this method INSIDE the locked transaction — the
     * freshest snapshot at apply time (concurrent listeners serialize on the
     * row lock, so the last one to apply always carries the latest aggregate
     * and no update is lost to an optimistic-version conflict).
     */
    Optional<ReviewStats> findStatsByProviderId(UUID providerId);
}

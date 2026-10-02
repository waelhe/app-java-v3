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
     *
     * <p><b>W1 semantics (§4.4):</b> the VERIFIED aggregate — forward,
     * {@code origin = 'BOOKING'}, {@code PUBLISHED} reviews only. The plan's
     * backward-compatibility rule: the stored {@code rating_average} column
     * "يستمر بحمله وحده" — all-BOOKING legacy data makes this byte-identical
     * to the pre-W1 aggregate.
     */
    Optional<ReviewStats> findStatsByProviderId(UUID providerId);

    /**
     * W1 (§4.4): the resolve seam the stored-average listener needs — the
     * reviewed provider's USER id (the {@code reviews.provider_id} space,
     * A1) for a live review. Present iff the review row exists; the stats
     * lookups that follow may still answer empty (the recompute-is-truth
     * rule: an empty aggregate CLEARS the stored value rather than being
     * skipped — the difference this method exists to make possible).
     */
    Optional<UUID> findProviderUserIdByReviewId(UUID reviewId);

    /**
     * W1 (§4.4): the GENERAL (organic) aggregate — forward
     * {@code origin = 'ORGANIC'}, {@code PUBLISHED}, live reviews only.
     * Recomputed, never incrementally adjusted, exactly like its verified
     * sibling; empty when the provider has no published organic reviews.
     */
    Optional<ReviewStats> findGeneralStatsByProviderId(UUID providerId);

    /**
     * W1 (§4.4): the general-aggregate resolve-by-review form — the
     * listener's second recompute channel (the verified form is
     * {@link #findStatsByReviewId(UUID)}).
     */
    Optional<ReviewStats> findGeneralStatsByReviewId(UUID reviewId);
}


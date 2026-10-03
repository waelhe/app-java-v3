package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * W1 (yelp-level plan §4.4/§4.5 — the provider public page's reviews block):
 * one PUBLISHED review row as the public page composes it. The projection
 * the {@link PublishedReviewsPort} serves across the module boundary — the
 * shared-space twin of the reviews module's own {@code ReviewResponse},
 * carrying exactly the fields a public row renders and nothing else.
 *
 * <p>Field discipline (the {@code ProviderPublicPageResponse} whitelist
 * rule — "the record cannot leak what it does not declare"):
 * <ul>
 *   <li>no {@code bookingId} — the verified badge is {@code origin}, not a
 *       booking pointer;</li>
 *   <li>no {@code direction} — the block is the forward surface by
 *       construction ({@code CONSUMER_TO_PROVIDER});</li>
 *   <li>no {@code moderationStatus} — every row here is {@code PUBLISHED}
 *       (the visibility gate is the serving port's own contract);</li>
 *   <li>no {@code listingId} — the public row has no listing renderer
 *       today (a title would be a second cross-module join; the W2
 *       business page owns that decision, not this projection).</li>
 * </ul>
 *
 * <p>The reviewer identity block rides the row exactly as the plan's
 * §4.4 "هوية المراجع في الاستجابة" shapes it: a display name honouring
 * {@code pseudonymized_at} (resolved by the owning module), his published
 * count, and the helpful-vote count — the three batch-resolved blocks,
 * never per-review queries. W4 (§5 — G28) adds the row's click target:
 * {@code reviewerId} is the public reviewer page's own key
 * ({@code GET /api/v1/users/{id}/public}), so the "نقرة من مراجعة إلى
 * صفحة المراجع" link travels with the row itself.
 *
 * @param id                  the review's own id (the vote/flag/report target)
 * @param rating              1..5 (the V6 CHECK bounds)
 * @param comment             the public text, nullable
 * @param reply               the reviewed provider's one public reply, nullable
 * @param repliedAt           when the provider replied, nullable
 * @param createdAt           when the review was written
 * @param origin              {@code BOOKING} («موثّقة») or {@code ORGANIC}
 *                            («عامة») — the V85 provenance column
 * @param reviewerId          the reviewer's user id — the public reviewer
 *                            page's key (W4/G28; already the public key of
 *                            {@code GET /api/v1/reviews/reviewer/{id}})
 * @param reviewerName        the pseudonym-honouring display name
 * @param reviewerReviewCount the reviewer's published-review count
 * @param helpfulCount        how many neighbours marked the review helpful
 */
public record PublishedReviewView(
        UUID id,
        Integer rating,
        String comment,
        String reply,
        Instant repliedAt,
        Instant createdAt,
        String origin,
        UUID reviewerId,
        String reviewerName,
        long reviewerReviewCount,
        long helpfulCount
) {

    /**
     * The origin value the reviews module stores for a booking-gated review
     * (the V85 {@code origin} column's CHECK-held vocabulary, mirrored here
     * because THIS record is the cross-module contract that carries it —
     * consumers outside the reviews module must not hardcode the literal).
     */
    public static final String ORIGIN_BOOKING = "BOOKING";

    /** The origin value the reviews module stores for an organic review ({@code ORGANIC}). */
    public static final String ORIGIN_ORGANIC = "ORGANIC";
}

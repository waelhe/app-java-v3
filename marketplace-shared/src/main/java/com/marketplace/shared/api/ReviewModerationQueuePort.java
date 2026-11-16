package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * The manager console's review-moderation queue board — the cross-module
 * seam the web lever reads and moves through (the {@code ReviewLookupPort}
 * house sibling: the interface lives in shared-api, the reviews module
 * implements it in its spi package, the app root's queue board injects it
 * — no module boundary is crossed in code).
 */
public interface ReviewModerationQueuePort {

    /**
     * The moderation inbox's actionable head: the PENDING_REVIEW items on
     * the service's own documented drain order (the highest fraud-signal
     * count first), bounded by {@code limit} — the deep history stays on
     * the REST surface's deterministic pagination.
     */
    List<ReviewModerationQueueEntry> pendingModerationQueue(int limit);

    /**
     * The publish verdict — the SAME {@code approve} service method the
     * module's own REST admin controller calls: the review joins the
     * published set and the provider's averages recompute with it.
     * The service's own refusal vocabulary (409's already-decided, 404's
     * unknown row) surfaces unchanged.
     */
    void approveReview(UUID reviewId);

    /**
     * The hide verdict — the SAME {@code reject} service method the
     * module's own REST admin controller calls: the review leaves the
     * published set and the averages recompute without it.
     */
    void rejectReview(UUID reviewId);
}

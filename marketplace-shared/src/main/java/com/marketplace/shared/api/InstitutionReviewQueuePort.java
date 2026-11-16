package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * The manager console's institution-review queue board — the cross-module
 * seam the web lever reads and moves through (the
 * {@code UrgentAlertsPort} sibling in the institutions module's own spi
 * package: the interface lives in shared-api, the registry owner
 * implements it, the app root's queue board injects it — the flat module
 * graph stays flat).
 */
public interface InstitutionReviewQueuePort {

    /**
     * The registry's actionable head: the PENDING trust-mark claims on
     * the service's own documented drain order (the oldest pending claim
     * first), bounded by {@code limit} — the deep history stays on the
     * REST surface's deterministic pagination.
     */
    List<InstitutionReviewQueueEntry> pendingReviewQueue(int limit);

    /**
     * The verdict's ONLY mover in both directions — the SAME
     * {@code review} service method the module's own REST admin
     * controller calls (no parallel write path): APPROVE lands the trust
     * mark, REJECT refuses the PENDING claim. The service's own refusal
     * vocabulary (409's already-decided transition, 404's unknown row)
     * surfaces unchanged.
     */
    void reviewInstitution(UUID institutionId, boolean approve);
}

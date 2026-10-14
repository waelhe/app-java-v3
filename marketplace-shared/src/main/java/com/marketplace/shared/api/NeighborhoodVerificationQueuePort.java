package com.marketplace.shared.api;

import java.util.List;
import java.util.UUID;

/**
 * The manager console's neighborhood-verification queue board — the
 * cross-module seam the web lever reads and moves through. The
 * {@code CommunityMembershipPort} house pattern verbatim: the interface
 * lives in shared-api, the data owner (marketplace-community, the
 * V60/V91 membership machinery) implements it in its spi package, the
 * consumer (the app root's console queue board) injects it — no module
 * boundary is crossed in code and the Reactor's module graph stays
 * flat ({@code ModulithVerificationTest} guards the seam).
 */
public interface NeighborhoodVerificationQueuePort {

    /**
     * The queue's actionable head: the PENDING claims on the service's
     * own documented drain order (the oldest pending claim first — the
     * queue's own clock), bounded by {@code limit} — never the whole
     * ledger (the deep history stays on the REST surface's
     * deterministic pagination).
     */
    List<NeighborhoodVerificationQueueEntry> pendingVerificationQueue(int limit);

    /**
     * The verdict's ONLY mover in both directions — the SAME
     * {@code reviewVerification} service method the module's own REST
     * admin controller calls (no parallel write path): APPROVE admits a
     * PENDING claim and re-admits a REJECTED one, REJECT refuses a
     * PENDING claim. The service's own refusal vocabulary (409's
     * already-decided, 404's unknown row) surfaces unchanged.
     */
    void reviewVerification(UUID membershipId, boolean approve);
}

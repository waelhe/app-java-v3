package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The neighborhood-verification queue's board row — the actionable head
 * the operator's screen renders (who is asking to be verified as a
 * neighbor, anchored to which level-3 geo node, in which state, last
 * moved when). The components are named for the queue board's own
 * template contract verbatim — the board reads them as record
 * accessors, nothing more.
 *
 * <p>A shared-api record by the {@code BookingSummary} precedent: the
 * entry is the CROSS-MODULE read shape, so it lives where both sides
 * see it — the owning module's adapter maps its own view onto it, the
 * consumer (the app root's queue board) renders it. The module's own
 * richer view never crosses the boundary.</p>
 */
public record NeighborhoodVerificationQueueEntry(
        UUID id,
        UUID userId,
        UUID locationId,
        String verificationState,
        Instant createdAt,
        Instant updatedAt
) {
}

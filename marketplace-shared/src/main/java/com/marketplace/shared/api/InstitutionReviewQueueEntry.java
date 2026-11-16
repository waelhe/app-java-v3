package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The institution-review queue's board row — the registry's actionable
 * head: which institution is asking for the trust mark, of which type,
 * anchored to which level-3 geo node, registered by which
 * representative. The components are named for the queue board's own
 * template contract verbatim.
 *
 * <p>A shared-api record by the {@code BookingSummary} precedent — the
 * cross-module read shape lives where both sides see it; the owning
 * module's adapter maps its entity onto it (the mapping stays inside
 * the module, the consumer never sees the entity).</p>
 */
public record InstitutionReviewQueueEntry(
        UUID id,
        String name,
        String type,
        UUID locationId,
        UUID representativeId
) {
}

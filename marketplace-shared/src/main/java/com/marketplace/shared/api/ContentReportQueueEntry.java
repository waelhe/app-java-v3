package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * The community-reports queue's board row — the open reports' actionable
 * head: who reported what kind of content (which id), for which reason,
 * when. The components are named for the queue board's own template
 * contract verbatim.
 *
 * <p>A shared-api record by the {@code BookingSummary} precedent — the
 * cross-module read shape lives where both sides see it; the community
 * module's adapter maps its own {@code ContentReportView} onto it, the
 * consumer never sees the module's types.</p>
 */
public record ContentReportQueueEntry(
        UUID id,
        UUID reporterId,
        String targetType,
        UUID targetId,
        String reason,
        Instant createdAt
) {
}

package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one notification addressed to the requester. The
 * table is recipient-scoped by design ({@code recipient_id}, V18) — the
 * plan's own provenance note — so the notification message (content
 * addressed to him) is his to export. Internal system columns are excluded
 * by the plan's explicit exclusion rule.
 */
public record NotificationExportEntry(
        UUID id,
        String type,
        String message,
        boolean read,
        Instant createdAt,
        Instant updatedAt
) {
}

package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — criterion 7): one saved search in the
 * account's Art. 20 export (the b-2 self-service read the identity module
 * aggregates). The criteria travel as their canonical JSON text — the
 * export is a faithful copy of what the subject stored, not a re-typed
 * projection.
 */
public record SavedSearchExportEntry(
        UUID id,
        String criteriaJson,
        boolean alertEnabled,
        Instant matchedAt,
        Instant createdAt
) {
}

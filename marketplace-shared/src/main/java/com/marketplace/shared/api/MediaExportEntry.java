package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one media asset owned by the requester — "وصف
 * وسائطه (بيانات وصفية لا بايتات الملفات)" verbatim: descriptive
 * metadata, never file bytes. The storage {@code objectKey} identifies
 * which object is his (the key is server-generated from his target — the
 * listing or the post — and the asset UUID); the derived thumbnail key is
 * absent — it is a system-derived artifact, not his provided data. Internal
 * system columns are excluded by the plan's explicit exclusion rule.
 *
 * <p><b>L48:</b> exactly one of {@code listingId}/{@code postId} is
 * non-null — the asset's target, surfaced so the export describes which
 * listing OR which neighborhood post each photo belongs to (the same
 * honesty the pre-L48 export gave the listing target alone).
 */
public record MediaExportEntry(
        UUID id,
        UUID listingId,
        UUID postId,
        String objectKey,
        String contentType,
        long sizeBytes,
        String status,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}

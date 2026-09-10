package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract, gate b-5): one media asset owned by the requester — "وصف
 * وسائطه (بيانات وصفية لا بايتات الملفات)" verbatim: descriptive
 * metadata, never file bytes. The storage {@code objectKey} identifies
 * which object is his (the key is server-generated from his listing and
 * the asset UUID); the derived thumbnail key is absent — it is a
 * system-derived artifact, not his provided data. Internal system columns
 * are excluded by the plan's explicit exclusion rule.
 */
public record MediaExportEntry(
        UUID id,
        UUID listingId,
        String objectKey,
        String contentType,
        long sizeBytes,
        String status,
        int position,
        Instant createdAt,
        Instant updatedAt
) {
}

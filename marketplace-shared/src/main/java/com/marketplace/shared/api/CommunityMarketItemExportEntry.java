package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L50 (the neighborhood market board): one market item in the account's
 * Art. 20 export (the b-2 self-service read the identity module
 * aggregates through {@code CommunityExportPort}).
 *
 * <p>The export is a faithful copy of the stored facts, not a re-typed
 * projection (the post entry's own discipline): the neighborhood
 * travels as its {@code geo_locations} id, the category, condition and
 * status as the stored enum names, the price as the stored integer
 * cents and its ISO 4217 code. Withdrawn (author-soft-deleted) items
 * are included — the b-5 discrimination: surface deletion is a
 * visibility flag, not an erasure; a purged account's items carry the
 * {@code [purged]} tombstone texts the b-3 adapter leaves.
 */
public record CommunityMarketItemExportEntry(
        UUID id,
        UUID locationId,
        String category,
        String title,
        String condition,
        Long priceCents,
        String priceCurrency,
        String status,
        String locationLabel,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}

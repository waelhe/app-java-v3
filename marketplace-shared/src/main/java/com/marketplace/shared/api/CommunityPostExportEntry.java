package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L42 (neighborhood community plan §5): one neighborhood post in the
 * account's Art. 20 export (the b-2 self-service read the identity
 * module aggregates through {@code CommunityExportPort}).
 *
 * <p>The export is a faithful copy of the stored facts, not a re-typed
 * projection (the membership entry's own discipline): the location
 * travels as its {@code geo_locations} id, the category and status as
 * the stored enum names. Soft-deleted (author-deleted) posts are
 * included — the b-5 discrimination: surface deletion is a visibility
 * flag, not an erasure; a purged account's posts carry the
 * {@code [purged]} tombstone texts the b-3 adapter left.
 */
public record CommunityPostExportEntry(
        UUID id,
        UUID locationId,
        String category,
        String title,
        String body,
        String status,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}

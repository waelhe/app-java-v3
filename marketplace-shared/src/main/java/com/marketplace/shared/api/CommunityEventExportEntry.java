package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer):
 * one neighborhood event the subject ORGANIZED, in the account's Art. 20
 * export (the b-2 self-service read the identity module aggregates
 * through {@code CommunityExportPort}).
 *
 * <p>The export is a faithful copy of the stored facts, not a re-typed
 * projection (the post entry's own discipline): the location travels as
 * its {@code geo_locations} id, the category and registration model as
 * the stored enum names, the display labels verbatim as the organizer
 * wrote them. Soft-deleted (organizer-deleted) events are included —
 * the b-5 discrimination: surface deletion is a visibility flag, not
 * an erasure; a purged account's events carry the {@code [purged]}
 * tombstone texts the b-3 adapter left.
 */
public record CommunityEventExportEntry(
        UUID id,
        UUID locationId,
        String category,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        String locationLabel,
        String organizerLabel,
        Integer capacity,
        String registration,
        boolean featured,
        Instant createdAt,
        Instant updatedAt,
        boolean deleted
) {
}

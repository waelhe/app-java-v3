package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The board's read model (L49): the stored facts and nothing else —
 * the NeighborhoodPostView projection discipline verbatim. The author
 * stays an opaque UUID (the identity seams own any resolution the
 * client does); the geo tree's display names stay the public geo
 * surface's concern — the event carries the product's own display
 * labels ({@code locationLabel} / {@code organizerLabel}) as the
 * organizer wrote them.
 *
 * <p><b>The caller-scoped attendance facts (the L47 reaction shape
 * verbatim):</b> {@code attending} (the live seats on the event — the
 * grouped count over the page's ids; the same number for every reader)
 * and {@code rsvpedByMe} (the caller's own live seat, so the client
 * renders the joined state honestly — the projection's one per-reader
 * field).
 */
public record NeighborhoodEventView(
        UUID id,
        UUID authorId,
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
        long attending,
        boolean rsvpedByMe,
        Instant createdAt,
        Instant updatedAt
) {
    /**
     * The write paths' echo: a fresh event no one has seated yet —
     * the zero-fresh stance the post view's own single-argument factory
     * carries.
     */
    static NeighborhoodEventView of(NeighborhoodEvent event) {
        return new NeighborhoodEventView(
                event.getId(),
                event.getAuthorId(),
                event.getLocationId(),
                event.getCategory().name(),
                event.getTitle(),
                event.getDescription(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getLocationLabel(),
                event.getOrganizerLabel(),
                event.getCapacity(),
                event.getRegistration().name(),
                event.isFeatured(),
                0L,
                false,
                event.getCreatedAt(),
                event.getUpdatedAt());
    }

    /**
     * The board read's factory: the stored facts plus the two
     * caller-scoped attendance facts the grouped count and the
     * caller's own live seat produced.
     */
    static NeighborhoodEventView of(NeighborhoodEvent event, long attending, boolean rsvpedByMe) {
        return new NeighborhoodEventView(
                event.getId(),
                event.getAuthorId(),
                event.getLocationId(),
                event.getCategory().name(),
                event.getTitle(),
                event.getDescription(),
                event.getStartsAt(),
                event.getEndsAt(),
                event.getLocationLabel(),
                event.getOrganizerLabel(),
                event.getCapacity(),
                event.getRegistration().name(),
                event.isFeatured(),
                attending,
                rsvpedByMe,
                event.getCreatedAt(),
                event.getUpdatedAt());
    }
}

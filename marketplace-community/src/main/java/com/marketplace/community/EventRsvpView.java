package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The RSVP read model (L49): the stored facts and nothing else — the
 * PostReactionView projection discipline verbatim (the member stays an
 * opaque UUID; the seat is reached through the event gate, so it never
 * re-projects the event's own state).
 */
public record EventRsvpView(
        UUID id,
        UUID eventId,
        UUID memberId,
        Instant createdAt,
        Instant updatedAt
) {
    static EventRsvpView of(EventRsvp rsvp) {
        return new EventRsvpView(
                rsvp.getId(),
                rsvp.getEventId(),
                rsvp.getMemberId(),
                rsvp.getCreatedAt(),
                rsvp.getUpdatedAt());
    }
}

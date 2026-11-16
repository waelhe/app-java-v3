package com.marketplace.community;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L49 — the event and RSVP entities' own factory facts (the
 * PostReactionTest precedent): the factories stamp the columns verbatim
 * and nothing else; every gate (membership match, level, time order,
 * registration/capacity coherence, one-seat, capacity count) lives in
 * the service before any write.
 */
class NeighborhoodEventTest {

    private static final Instant STARTS = Instant.parse("2026-10-02T08:00:00Z");
    private static final Instant ENDS = Instant.parse("2026-10-02T11:00:00Z");

    @Test
    void eventFactory_carriesEveryAuthoredColumnAndFeaturedStaysFalse() {
        UUID authorId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodEvent event = NeighborhoodEvent.event(authorId, locationId,
                EventCategory.VOLUNTEER, "Park cleanup morning", "Tools provided.",
                STARTS, ENDS, "Community garden — main gate", "Development committee",
                20, EventRegistration.LIMITED_SEATS);

        assertThat(event.getId()).isNotNull();
        assertThat(event.getAuthorId()).isEqualTo(authorId);
        assertThat(event.getLocationId()).isEqualTo(locationId);
        assertThat(event.getCategory()).isEqualTo(EventCategory.VOLUNTEER);
        assertThat(event.getTitle()).isEqualTo("Park cleanup morning");
        assertThat(event.getDescription()).isEqualTo("Tools provided.");
        assertThat(event.getStartsAt()).isEqualTo(STARTS);
        assertThat(event.getEndsAt()).isEqualTo(ENDS);
        assertThat(event.getLocationLabel()).isEqualTo("Community garden — main gate");
        assertThat(event.getOrganizerLabel()).isEqualTo("Development committee");
        assertThat(event.getCapacity()).isEqualTo(20);
        assertThat(event.getRegistration()).isEqualTo(EventRegistration.LIMITED_SEATS);
        // featured is a read-side flag the create contract does NOT
        // accept — the factory's own honest zero-fresh stance.
        assertThat(event.isFeatured()).isFalse();
    }

    @Test
    void eventFactory_openEndCarriesNullEndsAt() {
        // A gathering may be open-ended — the design's own contract
        // (the demo dataset's ديوانية carries endsAt: null).
        NeighborhoodEvent event = NeighborhoodEvent.event(
                UUID.randomUUID(), UUID.randomUUID(),
                EventCategory.SOCIAL, "Monthly majlis", "Open session.",
                STARTS, null, "South-gate majlis", "The neighborhood majlis",
                null, EventRegistration.OPEN);

        assertThat(event.getEndsAt()).isNull();
        assertThat(event.getCapacity()).isNull();
        assertThat(event.getRegistration()).isEqualTo(EventRegistration.OPEN);
    }

    @Test
    void eventFactory_freshInstancesAreIndependent() {
        UUID authorId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        NeighborhoodEvent first = NeighborhoodEvent.event(authorId, locationId,
                EventCategory.SOCIAL, "A", "B", STARTS, null, "C", "D",
                null, EventRegistration.OPEN);
        NeighborhoodEvent second = NeighborhoodEvent.event(authorId, locationId,
                EventCategory.SOCIAL, "A", "B", STARTS, null, "C", "D",
                null, EventRegistration.OPEN);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }

    @Test
    void rsvpFactory_carriesTheIdPairAndNothingElse() {
        UUID eventId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        EventRsvp seat = EventRsvp.rsvp(eventId, memberId);

        assertThat(seat.getId()).isNotNull();
        assertThat(seat.getEventId()).isEqualTo(eventId);
        assertThat(seat.getMemberId()).isEqualTo(memberId);
    }

    @Test
    void rsvpFactory_freshInstancesAreIndependent() {
        // One seat per member is the repository's unique index, not a
        // shared identity: two factory calls produce two distinct rows —
        // the backstop's own shape.
        UUID eventId = UUID.randomUUID();
        UUID memberId = UUID.randomUUID();

        EventRsvp first = EventRsvp.rsvp(eventId, memberId);
        EventRsvp second = EventRsvp.rsvp(eventId, memberId);

        assertThat(first.getId()).isNotEqualTo(second.getId());
    }
}

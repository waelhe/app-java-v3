package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L49 — the events service's gate orders, unit-pinned (the module
 * integration test proves the same against the real schema):
 *
 * <ul>
 *   <li>the organize gate order: geo resolve (the port's own 404) →
 *       level-3 (400) → active membership in exactly that location
 *       (403 — absent OR a different neighborhood) → the event's own
 *       type gates (the strictly-future start, the time order, the ONE
 *       registration/capacity rule — each a 400) → insert;</li>
 *   <li>the board read gate: no active membership ⇒ 403 (G-N3's
 *       default); the board carries attending + rsvpedByMe per row;</li>
 *   <li>the RSVP gate order: the LOCKED event gate's honest 404 →
 *       membership-in-location (403) → one-seat (409) → capacity
 *       (409) → insert;</li>
 *   <li>the un-RSVP: the same event/membership gates, no live seat ⇒
 *       the honest 404, the free is soft;</li>
 *   <li>the organizer delete: only the organizer (403 otherwise), soft,
 *       never physical.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodEventServiceTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T09:30:00Z");

    @Mock
    private NeighborhoodEventRepository eventRepository;

    @Mock
    private EventRsvpRepository rsvpRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

    @Mock
    private GeoLookupPort geoLookupPort;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private NeighborhoodEventService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodEventService(eventRepository, rsvpRepository,
                membershipRepository, geoLookupPort, clock);
    }

    private UUID authorId = UUID.randomUUID();
    private UUID memberId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();
    private UUID eventId = UUID.randomUUID();

    private GeoLookupPort.GeoNode node(int level) {
        return new GeoLookupPort.GeoNode(locationId, null, level, "حي", null, "node");
    }

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location, clock);
    }

    private NeighborhoodEvent eventIn(UUID author, UUID location, Integer capacity,
                                      EventRegistration registration) {
        return NeighborhoodEvent.event(author, location,
                EventCategory.VOLUNTEER, "Park cleanup morning", "Tools provided.",
                FIXED.plusSeconds(86_400), FIXED.plusSeconds(86_400 + 10_800),
                "Community garden — main gate", "Development committee",
                capacity, registration);
    }

    // ---------- createEvent ----------

    @Test
    void createEvent_unknownLocation_isThePortsOwn404() {
        when(geoLookupPort.getLocation(locationId))
                .thenThrow(new ResourceNotFoundException("Location", locationId));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_nonLevel3Node_is400BeforeAnyWrite() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(2));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level-3");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_noMembership_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_membershipInAnotherNeighborhood_is403() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("your own neighborhood");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_pastStart_is400TheBoardIsForwardLooking() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.minusSeconds(3600), null, "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("future");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_endBeforeStart_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(7200), FIXED.plusSeconds(3600),
                "Spot", "Committee", null, EventRegistration.OPEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("endsAt must be after startsAt");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_openEventWithCapacity_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", 20, EventRegistration.OPEN))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("OPEN event carries no capacity");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_seatedEventWithoutCapacity_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", null,
                EventRegistration.LIMITED_SEATS))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly positive capacity");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_seatedEventWithZeroCapacity_is400() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));

        assertThatThrownBy(() -> service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Title", "Body",
                FIXED.plusSeconds(3600), null, "Spot", "Committee", 0,
                EventRegistration.TABLE_RESERVATION))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("strictly positive capacity");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_memberOrganizes_answerIs201ShapedAndFeaturedStaysFalse() {
        when(geoLookupPort.getLocation(locationId)).thenReturn(node(3));
        when(membershipRepository.findByUserId(authorId))
                .thenReturn(Optional.of(membershipOf(authorId, locationId)));
        when(eventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        NeighborhoodEventView view = service.createEvent(authorId, locationId,
                EventCategory.VOLUNTEER, "Park cleanup morning", "Tools provided.",
                FIXED.plusSeconds(3600), FIXED.plusSeconds(7200),
                "Community garden — main gate", "Development committee",
                20, EventRegistration.LIMITED_SEATS);

        assertThat(view.attending()).isZero();
        assertThat(view.rsvpedByMe()).isFalse();
        // featured is a read-side flag the create contract does NOT accept —
        // the honest zero-fresh stance (no fake curation).
        assertThat(view.featured()).isFalse();
        assertThat(view.registration()).isEqualTo("LIMITED_SEATS");
        assertThat(view.capacity()).isEqualTo(20);
        verify(eventRepository).save(any(NeighborhoodEvent.class));
    }

    // ---------- getBoard ----------

    @Test
    void getBoard_noMembership_is403() {
        when(membershipRepository.findByUserId(memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBoard(memberId, null, Pageable.unpaged()))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
    }

    @Test
    void getBoard_carriesTheTwoAttendanceFactsPerRow() {
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        NeighborhoodEvent one = eventIn(authorId, locationId, 20, EventRegistration.LIMITED_SEATS);
        NeighborhoodEvent two = eventIn(authorId, locationId, null, EventRegistration.OPEN);
        when(eventRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(one, two)));
        when(rsvpRepository.countByEventIdIn(any()))
                .thenReturn(List.of(new RsvpCountRow(one.getId(), 7L)));
        when(rsvpRepository.findByMemberIdAndEventIdIn(any(), any()))
                .thenReturn(List.of(EventRsvp.rsvp(two.getId(), memberId)));

        Page<NeighborhoodEventView> board = service.getBoard(memberId, null, PageRequest.of(0, 20));

        assertThat(board.getContent()).hasSize(2);
        assertThat(board.getContent().get(0).attending()).isEqualTo(7L);
        assertThat(board.getContent().get(0).rsvpedByMe()).isFalse();
        assertThat(board.getContent().get(1).attending()).isZero();
        assertThat(board.getContent().get(1).rsvpedByMe()).isTrue();
    }

    @Test
    void getBoard_sortsSoonestFirstOnTheCompleteKey() {
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(eventRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
                any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getBoard(memberId, null, PageRequest.of(0, 20));

        // The complete sort key rides the caller's Pageable (D-N5) — the
        // board is forward-looking: starts_at ASC, id ASC.
        var captor = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findAll(
                any(org.springframework.data.jpa.domain.Specification.class), captor.capture());
        Sort.Order first = captor.getValue().getSort().iterator().next();
        assertThat(first.getProperty()).isEqualTo("startsAt");
        assertThat(first.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    /** The grouped count projection's own row (the mock's shape). */
    private record RsvpCountRow(UUID eventId, long totalCount) implements EventRsvpRepository.EventRsvpCount {
        @Override
        public UUID getEventId() {
            return eventId;
        }

        @Override
        public long getTotalCount() {
            return totalCount;
        }
    }

    // ---------- rsvp ----------

    @Test
    void rsvp_unknownEvent_isTheHonest404ThroughTheLockedFind() {
        when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void rsvp_noMembership_is403() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Join a neighborhood");
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void rsvp_membershipInAnotherNeighborhood_is403() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, UUID.randomUUID())));

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("event's neighborhood");
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void rsvp_alreadySeated_is409OneSeatPerMember() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.of(EventRsvp.rsvp(eventId, memberId)));

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("One seat per member");
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void rsvp_seatsFull_is409TheCapacityGate() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, 2, EventRegistration.LIMITED_SEATS)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.empty());
        when(rsvpRepository.countByEventId(eventId)).thenReturn(2L);

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("seats are full");
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void rsvp_eventAlreadyStarted_is409TheWindowGate() {
        // The Greptile round-1 adoption: what the forward-looking board
        // won't show, the write won't accept — an event whose start has
        // passed answers 409 (the un-RSVP stays open past the start on
        // purpose: freeing one's own seat is personal-data management).
        NeighborhoodEvent past = NeighborhoodEvent.event(authorId, locationId,
                EventCategory.SOCIAL, "Already started", "Body",
                FIXED.minusSeconds(3600), null, "Spot", "Committee",
                null, EventRegistration.OPEN);
        when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(past));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));

        assertThatThrownBy(() -> service.rsvp(memberId, eventId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already started");
        verify(rsvpRepository, never()).save(any());
    }

    @Test
    void unrsvp_pastEvent_stillFreesTheSeat() {
        // The window gate closes the RSVP alone — the un-RSVP is the
        // member's own personal-data management and stays open.
        NeighborhoodEvent past = NeighborhoodEvent.event(authorId, locationId,
                EventCategory.SOCIAL, "Already started", "Body",
                FIXED.minusSeconds(3600), null, "Spot", "Committee",
                null, EventRegistration.OPEN);
        when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.of(past));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        EventRsvp seat = EventRsvp.rsvp(eventId, memberId);
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.of(seat));

        service.unrsvp(memberId, eventId);

        verify(rsvpRepository).delete(seat);
    }

    @Test
    void rsvp_oneSeatLeft_answerIsTheSeat() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, 2, EventRegistration.LIMITED_SEATS)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.empty());
        when(rsvpRepository.countByEventId(eventId)).thenReturn(1L);
        when(rsvpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EventRsvpView seat = service.rsvp(memberId, eventId);

        assertThat(seat.eventId()).isEqualTo(eventId);
        assertThat(seat.memberId()).isEqualTo(memberId);
        verify(rsvpRepository).save(any(EventRsvp.class));
    }

    @Test
    void rsvp_openEvent_neverCapacityGates() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.empty());
        when(rsvpRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.rsvp(memberId, eventId);

        // OPEN carries no capacity to count — the count read never runs.
        verify(rsvpRepository, never()).countByEventId(any());
    }

    // ---------- unrsvp ----------

    @Test
    void unrsvp_unknownEvent_isTheHonest404() {
        when(eventRepository.findByIdForUpdate(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unrsvp(memberId, eventId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unrsvp_noLiveSeat_isTheHonest404() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unrsvp(memberId, eventId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unrsvp_liveSeat_isSoftDeleted() {
        when(eventRepository.findByIdForUpdate(eventId))
                .thenReturn(Optional.of(eventIn(authorId, locationId, null, EventRegistration.OPEN)));
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        EventRsvp seat = EventRsvp.rsvp(eventId, memberId);
        when(rsvpRepository.findByEventIdAndMemberId(eventId, memberId))
                .thenReturn(Optional.of(seat));

        service.unrsvp(memberId, eventId);

        // The house soft delete — delete() through the repository, never a
        // physical remove: the row stays (b-5's retention).
        verify(rsvpRepository).delete(seat);
    }

    // ---------- deleteByOrganizer ----------

    @Test
    void deleteByOrganizer_unknownEvent_is404() {
        when(eventRepository.findById(eventId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteByOrganizer(authorId, eventId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteByOrganizer_notTheOrganizer_is403() {
        when(eventRepository.findById(eventId))
                .thenReturn(Optional.of(eventIn(UUID.randomUUID(), locationId, null, EventRegistration.OPEN)));

        assertThatThrownBy(() -> service.deleteByOrganizer(authorId, eventId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("organizer");
    }

    @Test
    void deleteByOrganizer_theOrganizer_isSoftDeleted() {
        NeighborhoodEvent event = eventIn(authorId, locationId, null, EventRegistration.OPEN);
        when(eventRepository.findById(eventId)).thenReturn(Optional.of(event));

        service.deleteByOrganizer(authorId, eventId);

        verify(eventRepository).delete(event);
    }
}

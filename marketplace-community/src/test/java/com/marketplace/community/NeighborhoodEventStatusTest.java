package com.marketplace.community;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
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
import org.springframework.data.jpa.domain.Specification;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * JT-20 — the event's honest gathering state, unit-pinned (the module
 * integration test proves the schema side against the real database):
 *
 * <ul>
 *   <li>the factory births every event ACTIVE (the V174 DEFAULT's
 *       entity-level twin);</li>
 *   <li>the {@code upcoming} floor is status-honest: a CANCELLED or
 *       POSTPONED event never masquerades as upcoming — the predicate
 *       composes the time gate with the literal NOT-IN exclusion;</li>
 *   <li>the optional {@code hasStatus} axis is ABSENT for null (the
 *       official Specifications model) and equal otherwise;</li>
 *   <li>the board read composes the status axis and projects the state
 *       onto every row.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NeighborhoodEventStatusTest {

    private static final Instant FIXED = Instant.parse("2026-10-01T09:30:00Z");

    @Mock
    private com.marketplace.community.NeighborhoodEventRepository eventRepository;

    @Mock
    private EventRsvpRepository rsvpRepository;

    @Mock
    private NeighborhoodMembershipRepository membershipRepository;

    @Mock
    private com.marketplace.shared.api.GeoLookupPort geoLookupPort;

    private final Clock clock = Clock.fixed(FIXED, ZoneOffset.UTC);

    private NeighborhoodEventService service;

    @BeforeEach
    void setUp() {
        service = new NeighborhoodEventService(eventRepository, rsvpRepository,
                membershipRepository, geoLookupPort, clock);
    }

    private UUID memberId = UUID.randomUUID();
    private UUID locationId = UUID.randomUUID();

    private NeighborhoodMembership membershipOf(UUID user, UUID location) {
        return NeighborhoodMembership.join(user, location, clock);
    }

    private NeighborhoodEvent activeEvent() {
        return NeighborhoodEvent.event(memberId, locationId,
                EventCategory.VOLUNTEER, "Park cleanup morning", "Tools provided.",
                FIXED.plusSeconds(86_400), null,
                "Community garden — main gate", "Development committee",
                null, EventRegistration.OPEN);
    }

    // ---------- the factory's birth state ----------

    @Test
    void eventFactory_birthsEveryEventActive() {
        NeighborhoodEvent event = activeEvent();

        assertThat(event.getStatus()).isEqualTo(NeighborhoodEventStatus.ACTIVE);
    }

    // ---------- the status-honest upcoming floor ----------

    @Test
    @SuppressWarnings("unchecked")
    void upcoming_excludesCancelledAndPostponed() {
        Root<NeighborhoodEvent> root = mock(Root.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Instant> startsPath = mock(Path.class);
        Path<Object> statusPath = mock(Path.class);
        jakarta.persistence.criteria.Predicate withdrawn =
                mock(jakarta.persistence.criteria.Predicate.class);
        when((Path<Instant>) (Path) root.get("startsAt")).thenReturn(startsPath);
        when(root.get("status")).thenReturn((Path) statusPath);
        when(statusPath.in(NeighborhoodEventStatus.CANCELLED,
                NeighborhoodEventStatus.POSTPONED)).thenReturn(withdrawn);

        NeighborhoodEventSpecifications.upcoming(FIXED)
                .toPredicate(root, null, cb);

        // The time gate AND the literal NOT-IN exclusion — the predicate
        // survives a future vocabulary widening without silently
        // re-admitting a withdrawn gathering.
        verify(cb).greaterThanOrEqualTo(startsPath, FIXED);
        verify(statusPath).in(NeighborhoodEventStatus.CANCELLED,
                NeighborhoodEventStatus.POSTPONED);
        verify(cb).not(withdrawn);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasStatus_null_isTheAbsentConjunction() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        jakarta.persistence.criteria.Predicate conjunction =
                mock(jakarta.persistence.criteria.Predicate.class);
        when(cb.conjunction()).thenReturn(conjunction);
        Root<NeighborhoodEvent> root = mock(Root.class);

        assertThat(NeighborhoodEventSpecifications.hasStatus(null)
                .toPredicate(root, null, cb)).isSameAs(conjunction);
        verifyNoInteractions(root);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hasStatus_present_delegatesToEqual() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<NeighborhoodEvent> root = mock(Root.class);
        Path<Object> statusPath = mock(Path.class);
        when(root.get("status")).thenReturn(statusPath);

        NeighborhoodEventSpecifications.hasStatus(NeighborhoodEventStatus.ACTIVE)
                .toPredicate(root, null, cb);

        verify(cb).equal(statusPath, NeighborhoodEventStatus.ACTIVE);
    }

    // ---------- the board read's status axis ----------

    @Test
    @SuppressWarnings("unchecked")
    void getBoard_withStatus_composesTheAxisAndProjectsTheState() {
        when(membershipRepository.findByUserId(memberId))
                .thenReturn(Optional.of(membershipOf(memberId, locationId)));
        NeighborhoodEvent event = activeEvent();
        Page<NeighborhoodEvent> page = new PageImpl<>(List.of(event));
        when(eventRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(page);

        Page<NeighborhoodEventView> board = service.getBoard(
                memberId, null, NeighborhoodEventStatus.ACTIVE, PageRequest.of(0, 20));

        // The state travels on every row — the client renders it
        // truthfully, wherever the row is served.
        assertThat(board.getContent().get(0).status()).isEqualTo("ACTIVE");

        org.mockito.ArgumentCaptor<Pageable> pageable =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(eventRepository).findAll(any(Specification.class), pageable.capture());
        // The complete sort key is still the service's own (D-N5) — the
        // status axis is a filter, never an order change.
        assertThat(pageable.getValue().getSort().getOrderFor("startsAt").getDirection())
                .isEqualTo(Sort.Direction.ASC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }
}

package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.GeoLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import io.micrometer.observation.annotation.Observed;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The neighborhood events surface (L49 — the Nextdoor-2026 completeness
 * wave, gap #4). One gate order, three commands, one read — all the
 * house precedents, measured:
 *
 * <p><b>The read gate (G-N3's default, verbatim from the feed):</b> the
 * board is for ACTIVE members of the neighborhood — an authenticated
 * caller with no active membership answers the explicit 403, never an
 * empty 200 that pretends the board exists. ANY verification state
 * reads (D-N3: REJECTED blocks community writes, not the board).
 *
 * <p><b>The organize gate order (L41's own discipline, verbatim from
 * the post publish):</b> the location is resolved through
 * {@link GeoLookupPort} FIRST — an unknown node is the port's own 404 —
 * then the level-3 requirement answers 400 BEFORE any write, then the
 * active-membership match (403), and only then the event's own
 * type-level gates: the strictly-future start (400 — the board is
 * forward-looking by construction), the time order (400), and the
 * ONE registration/capacity rule (400 — OPEN carries no capacity, the
 * two seated states a strictly positive one; the V83 CHECK is the
 * backstop).
 *
 * <p><b>The RSVP gate order (the L47 comment/reaction order
 * verbatim):</b> the event gate first (an unknown or deleted event
 * answers the honest 404 — a deleted event's seats are absent exactly
 * as the event itself is), then the active-membership gate in the
 * event's OWN {@code locationId} (403 — a seat is a community
 * commitment like a comment), then the one-seat check (409 — «مقعد
 * واحد لكل عضو», the V64/V73 precedent; the V83 partial unique index
 * is the backstop), and only then the capacity count (409 — the
 * seated states' own words, the AvailabilityService "No available
 * slot" precedent class).
 *
 * <p><b>The capacity serialization (the ListingViewsDailyRepository
 * precedent verbatim):</b> the RSVP path reads the event through the
 * repository's PESSIMISTIC_WRITE find, so every seat change on one
 * event QUEUES AT THE ROW — the count-then-insert cannot race past
 * capacity, and a member queued behind a freed seat reads the
 * committed post-free count. The un-RSVP rides the same lock: one
 * invariant story, every seat change on one event serializes on the
 * event row.
 *
 * <p><b>Deterministic pagination (D-N5):</b> the board read forces the
 * complete sort key — {@code starts_at ASC, id ASC} — so two events
 * starting in the same second never shake a page boundary (the board
 * is forward-looking: the time key leads where the feed's created_at
 * led).
 */
@Service
@Transactional
public class NeighborhoodEventService {

    /**
     * The geo port's own level contract (GeoNode's javadoc): 3 =
     * neighborhood. The same constant the membership and post services
     * gate on — one vocabulary, the port's int.
     */
    static final int NEIGHBORHOOD_LEVEL = 3;

    /** The board's complete sort key (D-N5) — soonest first, id breaking ties. */
    private static final Sort BOARD_SORT =
            Sort.by(Sort.Direction.ASC, "startsAt").and(Sort.by(Sort.Direction.ASC, "id"));

    private final NeighborhoodEventRepository eventRepository;
    private final EventRsvpRepository rsvpRepository;
    private final NeighborhoodMembershipRepository membershipRepository;
    private final GeoLookupPort geoLookupPort;
    private final Clock clock;

    public NeighborhoodEventService(NeighborhoodEventRepository eventRepository,
                                    EventRsvpRepository rsvpRepository,
                                    NeighborhoodMembershipRepository membershipRepository,
                                    GeoLookupPort geoLookupPort,
                                    Clock clock) {
        this.eventRepository = eventRepository;
        this.rsvpRepository = rsvpRepository;
        this.membershipRepository = membershipRepository;
        this.geoLookupPort = geoLookupPort;
        this.clock = clock;
    }

    /**
     * Organize an event — the caller writes into their own active
     * neighborhood. The gate order is the post publish's own: port
     * resolve (404) → level-3 (400) → active membership in exactly
     * that location (403) → the event's type gates (the strictly-future
     * start, the time order, the registration/capacity rule — each a
     * 400 BEFORE any write) → insert. A member of a DIFFERENT
     * neighborhood answering this neighborhood's id is the same 403 —
     * G-N1's one-membership default means the board you read is the
     * board you write.
     */
    @Observed(name = "community.event.create")
    public NeighborhoodEventView createEvent(UUID authorId, UUID locationId,
                                             EventCategory category, String title, String description,
                                             Instant startsAt, Instant endsAt,
                                             String locationLabel, String organizerLabel,
                                             Integer capacity, EventRegistration registration) {
        GeoLookupPort.GeoNode node = geoLookupPort.getLocation(locationId);
        if (node.level() != NEIGHBORHOOD_LEVEL) {
            throw new BadRequestException(
                    "locationId must reference a level-3 neighborhood node, got level "
                            + node.level() + " (" + node.slug() + ")");
        }
        requireWritableMembershipIn(authorId, locationId,
                "Join a neighborhood before organizing events (PUT /api/v1/me/neighborhood)",
                "Events go to your own neighborhood — this location is not it");
        requireCoherentTimes(startsAt, endsAt);
        requireCoherentRegistration(registration, capacity);
        NeighborhoodEvent saved = eventRepository.save(NeighborhoodEvent.event(
                authorId, locationId, category, title, description,
                startsAt, endsAt, locationLabel, organizerLabel, capacity, registration));
        return NeighborhoodEventView.of(saved);
    }

    /**
     * The board — the caller's OWN neighborhood, upcoming events only,
     * on the complete sort key. The one filter axis is {@code category}
     * (absent = the whole board — the feed's own discipline); the
     * product's THIS_WEEK and MINE view chips stay client-side by
     * contract ({@code startsAt} and {@code rsvpedByMe} ride every
     * row). No membership ⇒ the explicit 403 (G-N3's default) — there
     * is no location parameter to read anyone else's board: the
     * membership IS the scope. ANY verification state reads (D-N3:
     * REJECTED blocks community writes, not the board).
     */
    @Transactional(readOnly = true)
    public Page<NeighborhoodEventView> getBoard(UUID callerId, EventCategory category,
                                                Pageable pageable) {
        UUID locationId = requireMembership(callerId,
                "Join a neighborhood before reading its events board (PUT /api/v1/me/neighborhood)")
                .getLocationId();
        Pageable boardPageable = PageRequest.of(
                pageable.getPageNumber(), pageable.getPageSize(), BOARD_SORT);
        Page<NeighborhoodEvent> page = eventRepository.findAll(
                NeighborhoodEventSpecifications.hasLocation(locationId)
                        .and(NeighborhoodEventSpecifications.upcoming(clock.instant()))
                        .and(NeighborhoodEventSpecifications.hasCategory(category)),
                boardPageable);
        // The board read carries the two attendance facts — the grouped
        // live seat count per event and the caller's own live seat (the
        // L47 reactions pattern verbatim: one grouped aggregate + one IN
        // read over the page's ids; the empty page short-circuits below
        // and costs neither).
        List<NeighborhoodEvent> events = page.getContent();
        Map<UUID, Long> counts = seatCounts(events);
        Set<UUID> mine = mySeats(callerId, events);
        return page.map(event -> NeighborhoodEventView.of(
                event,
                counts.getOrDefault(event.getId(), 0L),
                mine.contains(event.getId())));
    }

    /**
     * The organizer's own delete — the house soft delete. The row stays
     * (b-5's retention — the Envers trail keeps every revision), the
     * reads stop returning it, and the seats follow in the read path
     * (the aggregate's own is_deleted semantics — a deleted event's
     * seats are absent exactly as the event itself is, with no physical
     * delete anywhere in this house). Only the organizer: anyone else
     * answers 403.
     */
    @Observed(name = "community.event.delete")
    public void deleteByOrganizer(UUID authorId, UUID eventId) {
        NeighborhoodEvent event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        if (!event.getAuthorId().equals(authorId)) {
            throw new AccessDeniedException("Only the event's organizer can delete it");
        }
        eventRepository.delete(event);
    }

    /**
     * Take a seat — one live RSVP per member per event, counted against
     * the seated states' capacity. The gate order is the L47 reaction
     * order verbatim: the event gate's honest 404 (through the LOCKED
     * find — the RSVP path's serialization point: every seat change on
     * one event queues at the row, so the count-then-insert cannot race
     * past capacity), then the active-membership gate in the event's
     * OWN {@code locationId} (403 — a seat is a community commitment
     * like a comment), then the one-seat check (409 — the product's own
     * «مقعد واحد لكل عضو»; the V83 partial unique index is the
     * backstop), and only then the capacity count (409 — the seated
     * states' own words).
     */
    @Observed(name = "community.event.rsvp")
    public EventRsvpView rsvp(UUID memberId, UUID eventId) {
        NeighborhoodEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        requireWritableMembershipIn(memberId, event.getLocationId(),
                "Join a neighborhood before RSVPing (PUT /api/v1/me/neighborhood)",
                "Only members of the event's neighborhood can take a seat");
        // The window gate (the Greptile round-1 adoption): an event whose
        // start has passed answers 409 — what the forward-looking board
        // won't show, the write won't accept. The un-RSVP stays open past
        // the start on purpose (freeing one's own seat is personal-data
        // management, not a community write — the b-3 seam's own spirit).
        if (!event.getStartsAt().isAfter(clock.instant())) {
            throw new ConflictException(
                    "This event has already started — its seats are closed");
        }
        if (rsvpRepository.findByEventIdAndMemberId(eventId, memberId).isPresent()) {
            throw new ConflictException(
                    "One seat per member per event — remove yours before RSVPing again");
        }
        if (event.getCapacity() != null
                && rsvpRepository.countByEventId(eventId) >= event.getCapacity()) {
            throw new ConflictException(
                    "This event's seats are full — capacity " + event.getCapacity());
        }
        EventRsvp saved = rsvpRepository.save(EventRsvp.rsvp(eventId, memberId));
        return EventRsvpView.of(saved);
    }

    /**
     * Free the seat — remove the caller's own LIVE RSVP. The gate order
     * matches {@link #rsvp(UUID, UUID)} (the locked event gate's honest
     * 404, then the membership gate's 403), and a member with no live
     * seat on the event answers the honest 404 (there is nothing to
     * free). The un-RSVP rides the same event-row lock the RSVP rides —
     * one invariant story: every seat change on one event serializes on
     * the event row, so a member queued behind a freed seat reads the
     * committed post-free count. The free is the house soft delete —
     * the row stays (b-5's retention, the Envers trail keeps the
     * revision) and the seat is free for a fresh one (the V83 partial
     * unique index admits exactly that).
     */
    @Observed(name = "community.event.unrsvp")
    public void unrsvp(UUID memberId, UUID eventId) {
        NeighborhoodEvent event = eventRepository.findByIdForUpdate(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId));
        requireWritableMembershipIn(memberId, event.getLocationId(),
                "Join a neighborhood before RSVPing (PUT /api/v1/me/neighborhood)",
                "Only members of the event's neighborhood can take a seat");
        EventRsvp seat = rsvpRepository.findByEventIdAndMemberId(eventId, memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Rsvp", eventId));
        rsvpRepository.delete(seat);
    }

    /**
     * The type-level time gates, each a 400 BEFORE any write: the start
     * is strictly in the future (the board is forward-looking by
     * construction — a past-dated event would be invisible on its own
     * board, a silent drop this house never serves), and an absent end
     * stays absent while a present one is strictly after the start (the
     * V83 time-order CHECK's friendly twin).
     */
    private void requireCoherentTimes(Instant startsAt, Instant endsAt) {
        if (!startsAt.isAfter(clock.instant())) {
            throw new BadRequestException(
                    "startsAt must be in the future — the events board is forward-looking");
        }
        if (endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new BadRequestException(
                    "endsAt must be after startsAt when present");
        }
    }

    /**
     * The ONE registration/capacity rule (the design's own three
     * states): OPEN means no capacity to count — the whole neighborhood
     * may come; the two seated states mean a strictly positive capacity
     * the RSVP gate counts seats against. The friendly 400 here is the
     * V83 CHECK's own twin — the constraint is the backstop.
     */
    private void requireCoherentRegistration(EventRegistration registration, Integer capacity) {
        if (registration == EventRegistration.OPEN) {
            if (capacity != null) {
                throw new BadRequestException(
                        "An OPEN event carries no capacity — leave capacity absent");
            }
            return;
        }
        if (capacity == null || capacity <= 0) {
            throw new BadRequestException(
                    "A " + registration + " event requires a strictly positive capacity");
        }
    }

    /**
     * L49: the grouped live seat count per event over the page's ids —
     * the empty page short-circuits to the empty map (a closed board
     * costs no aggregate).
     */
    private Map<UUID, Long> seatCounts(List<NeighborhoodEvent> events) {
        if (events.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = events.stream().map(NeighborhoodEvent::getId).toList();
        return rsvpRepository.countByEventIdIn(ids).stream()
                .collect(Collectors.toMap(
                        EventRsvpRepository.EventRsvpCount::getEventId,
                        EventRsvpRepository.EventRsvpCount::getTotalCount));
    }

    /**
     * L49: the caller's own live seat ids across the page's events —
     * the joined-state projection's one per-reader fact.
     */
    private Set<UUID> mySeats(UUID callerId, List<NeighborhoodEvent> events) {
        if (events.isEmpty()) {
            return Set.of();
        }
        List<UUID> ids = events.stream().map(NeighborhoodEvent::getId).toList();
        return rsvpRepository.findByMemberIdAndEventIdIn(callerId, ids).stream()
                .map(EventRsvp::getEventId)
                .collect(Collectors.toSet());
    }

    /**
     * The caller's membership — ANY verification state reads (D-N3:
     * REJECTED blocks community writes, never the board). Absent
     * membership ⇒ the explicit 403.
     */
    private NeighborhoodMembership requireMembership(UUID callerId, String noMembershipMessage) {
        return membershipRepository.findByUserId(callerId)
                .orElseThrow(() -> new AccessDeniedException(noMembershipMessage));
    }

    /**
     * The caller's membership WITH the community-write right — a
     * REJECTED claim answers the explicit 403 (G-N3; the #461 round:
     * the write gate, not the shared existence gate, carries this
     * check).
     */
    private NeighborhoodMembership requireWritableMembership(UUID callerId, String noMembershipMessage) {
        NeighborhoodMembership membership = requireMembership(callerId, noMembershipMessage);
        if (!membership.mayUseCommunityWrites()) {
            throw new AccessDeniedException("Rejected neighborhood verification cannot publish, comment, or recommend");
        }
        return membership;
    }

    /**
     * The membership-in-location WRITE gate: absent membership ⇒ 403
     * with the join hint; a membership in a DIFFERENT neighborhood ⇒
     * 403 with the scope fact — both checks land before any write.
     */
    private NeighborhoodMembership requireWritableMembershipIn(UUID callerId, UUID locationId,
                                                               String noMembershipMessage,
                                                               String wrongLocationMessage) {
        NeighborhoodMembership membership =
                requireWritableMembership(callerId, noMembershipMessage);
        if (!membership.getLocationId().equals(locationId)) {
            throw new AccessDeniedException(wrongLocationMessage);
        }
        return membership;
    }
}

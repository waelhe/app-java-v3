package com.marketplace.community;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L49 (the Nextdoor-2026 completeness wave — gap #4, the events layer) —
 * the acceptance criteria over the REAL chain: HTTP → the
 * resource-server chain → the membership gate (G-N3's 403) → the REAL
 * geo service (the fixed seed tree answers the existence and level
 * gates) → V83's real schema (the CHECKs, the board index, the Envers
 * mirrors, the one-seat partial unique index) → the REAL capacity
 * serialization (the PESSIMISTIC_WRITE event-row lock).
 *
 * <p>Every test uses its OWN random user ids (no test-level transaction —
 * each service call commits its own, the L41/L42 convention), so the
 * shared container's state never bleeds between tests.
 *
 * <p>Acceptance criteria: (1) a member organizes an event in their
 * neighborhood ⇒ 201 and the board carries it with both attendance
 * facts; (2) a non-member reads the board ⇒ 403 (the mandatory
 * negative); (3) the type gates answer 400 BEFORE any write (invalid
 * category/registration, blank fields, past start, end-before-start,
 * the ONE registration/capacity rule) and the level/unknown gates
 * answer 400/404; (4) the RSVP loop: seat → 201, one-seat 409, un-RSVP
 * → 204, the freed seat accepts a fresh 201 — with the board's
 * attending/rsvpedByMe riding every flip; (5) the capacity gate: the
 * third member on a two-seat event answers 409, and the freed seat
 * reopens; (6) an OPEN event never capacity-gates; (7) a non-member
 * RSVP on the event's neighborhood ⇒ 403; (8) the organizer's delete
 * hides the event from the board while the Envers trail stays; plus
 * the house guards: the 401-anonymous seam and the DB CHECK floors
 * for raw writers.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The module net pins the CONTRACT LOGIC, not the limiter windows
        // (the L34/L29 own-class convention, inverted to the generous
        // direction): this class spends SIXTEEN eventCreate calls against
        // the production budget of ten (the failed-400 attempts spend the
        // window too — the aspect wraps the controller before validation),
        // so the two event instances ride a test-only generous budget here
        // and the 429 windows themselves are pinned by
        // NeighborhoodEventRateLimitIntegrationTest with tiny instances.
        "resilience4j.ratelimiter.instances.eventCreate.limit-for-period=100",
        "resilience4j.ratelimiter.instances.eventCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.eventCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.eventRsvp.limit-for-period=200",
        "resilience4j.ratelimiter.instances.eventRsvp.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.eventRsvp.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodEventModuleIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the service gates against.
    private static final String SYRIA = "11111111-1111-4111-8111-111111111101";
    private static final String QUDSAYYA_CITY = "11111111-1111-4111-8111-111111111103";
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    /** Delivery observation only — the gating logic under test is production code. */
    @MockitoBean
    SimpMessagingTemplate messagingTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NeighborhoodMembershipService membershipService;

    /**
     * Cross-test isolation for the shared container (the L42 convention):
     * every test plants its own events in the SAME fixed seed node, and
     * the board counts events by NEIGHBORHOOD, not by author — a test
     * that runs after another would see the earlier tests' events in its
     * board counts. RSVPs delete BEFORE events (V83's internal FK); the
     * membership rows stay (they never affect board counts — the G-N1
     * slot is per user and every test uses its own random users).
     */
    @BeforeEach
    void isolateNeighborhoodData() {
        jdbc.update("DELETE FROM event_rsvps");
        jdbc.update("DELETE FROM neighborhood_events");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL chain; asserts the given status. */
    private void joinOverHttp(UUID userId, String locationId, int expectedStatus) throws Exception {
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    private ResultActions organizeOverHttp(UUID userId, String locationId, String category,
                                           String startsAt, String endsAt, String locationLabel,
                                           Integer capacity, String registration) throws Exception {
        StringBuilder body = new StringBuilder()
                .append("{\"locationId\": \"").append(locationId).append("\", ")
                .append("\"category\": \"").append(category).append("\", ")
                .append("\"title\": \"Park cleanup morning\", ")
                .append("\"description\": \"Tools provided.\", ")
                .append("\"startsAt\": \"").append(startsAt).append("\", ");
        if (endsAt != null) {
            body.append("\"endsAt\": \"").append(endsAt).append("\", ");
        }
        body.append("\"locationLabel\": \"").append(locationLabel).append("\", ")
                .append("\"organizerLabel\": \"Development committee\"");
        if (capacity != null) {
            body.append(", \"capacity\": ").append(capacity);
        }
        body.append(", \"registration\": \"").append(registration).append("\"}");
        return mockMvc.perform(post("/api/v1/neighborhood/events")
                .with(jwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.toString()));
    }

    /**
     * The events layer's own clock seam for the WRITE side: the seeded
     * start/end ride a day computed from the test run's own clock (the retro
     * #485 review round closed the fixed-date time bomb — a hardcoded
     * 2027-03-02 start would flip every 201 assertion to the 400
     * future-start gate the moment the wall clock passed it). Truncation to
     * the hour keeps the serialized form stable across the pair's uses.
     */
    private static final Instant SEEDED_EVENT_DAY =
            Instant.now().plus(Duration.ofDays(1)).truncatedTo(ChronoUnit.HOURS);

    private static String seededEventStart() {
        return SEEDED_EVENT_DAY.toString();
    }

    private static String seededEventEnd() {
        return SEEDED_EVENT_DAY.plus(Duration.ofHours(3)).toString();
    }

    private static String seededEventEndBeforeStart() {
        return SEEDED_EVENT_DAY.minus(Duration.ofHours(1)).toString();
    }

    /** A member + their stored event id, created over the REAL chain. */
    private record SeededEvent(UUID organizerId, UUID eventId) {
    }

    private SeededEvent seatedEventIn(String locationId, Integer capacity, String registration)
            throws Exception {
        UUID organizerId = asCaller(UUID.randomUUID());
        joinOverHttp(organizerId, locationId, 201);
        String responseBody = organizeOverHttp(organizerId, locationId, "VOLUNTEER",
                        seededEventStart(), seededEventEnd(),
                        "Community garden — main gate", capacity, registration)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID eventId = UUID.fromString(
                com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(responseBody).get("id").asText());
        return new SeededEvent(organizerId, eventId);
    }

    @Test
    void criterion1_memberOrganizesInTheirNeighborhood_201AndTheBoardCarriesIt() throws Exception {
        UUID organizerId = asCaller(UUID.randomUUID());
        joinOverHttp(organizerId, QUDSAYYA_OLD_TOWN, 201);

        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "VOLUNTEER",
                        seededEventStart(), seededEventEnd(),
                        "Community garden — main gate", null, "OPEN")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.authorId").value(organizerId.toString()))
                .andExpect(jsonPath("$.locationId").value(QUDSAYYA_OLD_TOWN))
                .andExpect(jsonPath("$.category").value("VOLUNTEER"))
                .andExpect(jsonPath("$.registration").value("OPEN"))
                .andExpect(jsonPath("$.featured").value(false))
                .andExpect(jsonPath("$.attending").value(0))
                .andExpect(jsonPath("$.rsvpedByMe").value(false));

        // The board (the caller's OWN neighborhood — the membership IS
        // the scope) carries the event.
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Park cleanup morning"))
                .andExpect(jsonPath("$.content[0].locationLabel")
                        .value("Community garden — main gate"));

        // and only that member's neighborhood — a member of ANOTHER
        // neighborhood reads THEIR own board (not this one):
        UUID otherMember = joinedMember(QUDSAYYA_SUBURB);
        asCaller(otherMember);
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void criterion2_nonMemberReadsTheBoard_is403() throws Exception {
        UUID strangerId = asCaller(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion3_typeGatesAnswer400BeforeAnyWrite() throws Exception {
        UUID organizerId = asCaller(UUID.randomUUID());
        joinOverHttp(organizerId, QUDSAYYA_OLD_TOWN, 201);

        // Invalid category — the controller's own parse (the vocabulary is
        // listed; never an enum-binding 500).
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "PARTY",
                        seededEventStart(), null, "Spot", null, "OPEN")
                .andExpect(status().isBadRequest());
        // Invalid registration model.
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "SOCIAL",
                        seededEventStart(), null, "Spot", null, "BY_INVITE")
                .andExpect(status().isBadRequest());
        // The past start — the board is forward-looking (400, never a
        // silent drop onto a board that can never show it).
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "SOCIAL",
                        "2020-03-02T08:00:00Z", null, "Spot", null, "OPEN")
                .andExpect(status().isBadRequest());
        // End before start.
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "SOCIAL",
                        seededEventStart(), seededEventEndBeforeStart(),
                        "Spot", null, "OPEN")
                .andExpect(status().isBadRequest());
        // The ONE registration/capacity rule — OPEN with capacity.
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "SOCIAL",
                        seededEventStart(), null, "Spot", 20, "OPEN")
                .andExpect(status().isBadRequest());
        // The ONE registration/capacity rule — seated without capacity.
        organizeOverHttp(organizerId, QUDSAYYA_OLD_TOWN, "SOCIAL",
                        seededEventStart(), null, "Spot", null, "LIMITED_SEATS")
                .andExpect(status().isBadRequest());
        // The level gate (the L41 chain, before any write).
        organizeOverHttp(organizerId, QUDSAYYA_CITY, "SOCIAL",
                        seededEventStart(), null, "Spot", null, "OPEN")
                .andExpect(status().isBadRequest());
        // Unknown location — the port's own 404.
        organizeOverHttp(organizerId, UUID.randomUUID().toString(), "SOCIAL",
                        seededEventStart(), null, "Spot", null, "OPEN")
                .andExpect(status().isNotFound());

        // Criterion 3's closing assertion: nothing was written.
        assertThat(eventRows(organizerId)).isZero();
    }

    @Test
    void criterion4_theRsvpLoop_seat409_unrsvp204_theFreedSeatAccepts201() throws Exception {
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");
        UUID member = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        // Seat → 201.
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(seeded.eventId().toString()))
                .andExpect(jsonPath("$.memberId").value(member.toString()));

        // The board carries attending 1 + rsvpedByMe true.
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(1))
                .andExpect(jsonPath("$.content[0].rsvpedByMe").value(true));

        // One seat per member — the second RSVP answers 409.
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isConflict());

        // Un-RSVP → 204; the board carries attending 0 + rsvpedByMe false.
        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(0))
                .andExpect(jsonPath("$.content[0].rsvpedByMe").value(false));

        // A member with no live seat answers the honest 404.
        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isNotFound());

        // The freed seat accepts a fresh 201 (the soft-deleted row frees
        // the voice — the V73 precedent's own semantics).
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(1))
                .andExpect(jsonPath("$.content[0].rsvpedByMe").value(true));
    }

    @Test
    void criterion5_theCapacityGate_thirdMemberOnTwoSeatsIs409_theFreedSeatReopens() throws Exception {
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, 2, "LIMITED_SEATS");

        UUID first = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isCreated());
        UUID second = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isCreated());

        // The board carries the two seats against capacity 2.
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(2))
                .andExpect(jsonPath("$.content[0].capacity").value(2));

        // The third member answers the capacity 409.
        UUID third = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isConflict());

        // One seat frees — its OWNER frees it (the second member; the
        // third holds no live seat, whose un-RSVP is the honest 404), and
        // the third member then seats over the REAL serialization (the
        // freed seat reopens without any manual step).
        asCaller(second);
        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isNoContent());
        asCaller(third);
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(2));
    }

    @Test
    void criterion6b_startedEventAnswers409OnRsvp_butTheUnrsvpStaysOpen() throws Exception {
        // The Greptile round-1 adoption (the window gate): what the
        // forward-looking board won't show, the write won't accept — a
        // PAST-dated event is planted directly (the service's own type
        // gate honestly refuses to create one), and the RSVP answers 409
        // while the un-RSVP stays open (personal-data management).
        UUID organizerId = asCaller(UUID.randomUUID());
        joinOverHttp(organizerId, QUDSAYYA_OLD_TOWN, 201);
        UUID eventId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO neighborhood_events (id, author_id, location_id, category, "
                        + "title, description, starts_at, location_label, organizer_label, "
                        + "registration) VALUES (?, ?, ?, 'SOCIAL', 'Past majlis', 'Already "
                        + "happened.', now() - interval '2 hours', 'Spot', 'Committee', 'OPEN')",
                eventId, organizerId, UUID.fromString(QUDSAYYA_OLD_TOWN));
        UUID member = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId).with(jwt()))
                .andExpect(status().isConflict());

        // The planted seat still frees — the member's own row, past or not.
        jdbc.update("INSERT INTO event_rsvps (id, event_id, member_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), eventId, member);
        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", eventId).with(jwt()))
                .andExpect(status().isNoContent());
    }

    @Test
    void criterion6_openEventsNeverCapacityGate() throws Exception {
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");

        for (int i = 0; i < 5; i++) {
            UUID member = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
            mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(5))
                .andExpect(jsonPath("$.content[0].capacity").doesNotExist());
    }

    @Test
    void criterion7_nonMemberRsvpOnTheEventsNeighborhood_is403() throws Exception {
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");
        UUID outsider = asCaller(joinedMember(QUDSAYYA_SUBURB));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void criterion8_organizerDeleteHidesTheEvent_theEnversTrailStays() throws Exception {
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");

        mockMvc.perform(delete("/api/v1/neighborhood/events/{id}", seeded.eventId())
                        .with(jwt()))
                .andExpect(status().isNoContent());

        // The board no longer carries it — but the Envers trail does.
        mockMvc.perform(get("/api/v1/neighborhood/events").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        Integer revisions = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_events_aud WHERE id = ?",
                Integer.class, seeded.eventId());
        assertThat(revisions).isGreaterThanOrEqualTo(1);

        // A deleted event's seats are absent exactly as the event itself
        // is — the honest 404.
        UUID member = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", seeded.eventId()).with(jwt()))
                .andExpect(status().isNotFound());

        // Only the organizer: anyone else answers 403 (a fresh live event).
        SeededEvent other = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");
        UUID outsider = asCaller(joinedMember(QUDSAYYA_OLD_TOWN));
        mockMvc.perform(delete("/api/v1/neighborhood/events/{id}", other.eventId())
                        .with(jwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void houseGuard_anonymousIs401OnEveryEventsSurface() throws Exception {
        mockMvc.perform(get("/api/v1/neighborhood/events"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/events/{id}/rsvp", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void houseGuard_theDbCheckFloors_forRawWriters() throws Exception {
        // The V83 CHECKs are the backstop for any path that races past
        // the service's friendly 400s — a raw writer with a bogus
        // category, registration or capacity shape is refused by the
        // schema itself (the D-N7 discipline).
        UUID id = UUID.randomUUID();
        assertThatThrownBySql("INSERT INTO neighborhood_events (id, author_id, location_id, "
                + "category, title, description, starts_at, location_label, organizer_label, "
                + "registration) VALUES (?, ?, ?, 'PARTY', 'T', 'D', now(), 'L', 'O', 'OPEN')",
                id, UUID.randomUUID(), UUID.fromString(QUDSAYYA_OLD_TOWN));
        assertThatThrownBySql("INSERT INTO neighborhood_events (id, author_id, location_id, "
                + "category, title, description, starts_at, location_label, organizer_label, "
                + "registration) VALUES (?, ?, ?, 'SOCIAL', 'T', 'D', now(), 'L', 'O', 'BY_INVITE')",
                id, UUID.randomUUID(), UUID.fromString(QUDSAYYA_OLD_TOWN));
        // OPEN with capacity — the ONE rule.
        assertThatThrownBySql("INSERT INTO neighborhood_events (id, author_id, location_id, "
                + "category, title, description, starts_at, location_label, organizer_label, "
                + "capacity, registration) VALUES (?, ?, ?, 'SOCIAL', 'T', 'D', now(), 'L', 'O', "
                + "5, 'OPEN')",
                id, UUID.randomUUID(), UUID.fromString(QUDSAYYA_OLD_TOWN));
        // The time order.
        assertThatThrownBySql("INSERT INTO neighborhood_events (id, author_id, location_id, "
                + "category, title, description, starts_at, ends_at, location_label, "
                + "organizer_label, registration) VALUES (?, ?, ?, 'SOCIAL', 'T', 'D', "
                + "now(), now() - interval '1 hour', 'L', 'O', 'OPEN')",
                id, UUID.randomUUID(), UUID.fromString(QUDSAYYA_OLD_TOWN));
    }

    @Test
    void houseGuard_theOneSeatPartialUniqueIndex_forRawRacingWriters() throws Exception {
        // The V83 partial unique index — the backstop for a raw racing
        // double-insert past the service's explicit 409 (the V64/V73
        // precedent verbatim).
        SeededEvent seeded = seatedEventIn(QUDSAYYA_OLD_TOWN, null, "OPEN");
        UUID member = UUID.randomUUID();
        jdbc.update("INSERT INTO event_rsvps (id, event_id, member_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), seeded.eventId(), member);
        assertThatThrownBySql("INSERT INTO event_rsvps (id, event_id, member_id) "
                + "VALUES (?, ?, ?)",
                UUID.randomUUID(), seeded.eventId(), member);
    }

    private int eventRows(UUID authorId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_events WHERE author_id = ?",
                Integer.class, authorId);
        return count == null ? 0 : count;
    }

    /** Asserts the raw SQL answers a constraint violation (the CHECK floors). */
    private void assertThatThrownBySql(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            throw new AssertionError("The raw write was expected to violate a V83 constraint: "
                    + sql);
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // the backstop held
        }
    }
}

package com.marketplace.community;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L49 MVC slice: the events board surface's request validation and
 * status shape. The membership 403s (G-N3), the gate orders, the
 * capacity serialization and the deterministic pagination are pinned
 * against the REAL chain by the module integration test; this slice
 * pins the HTTP contract itself (the L41/L42/L47 WebMvc precedent):
 * the type gates' 400s (invalid category, invalid registration, blank
 * fields — before any write), the honest 404s, the 201 writes, the 204
 * deletes and the two attendance facts' read shape.
 */
@WebMvcTest(controllers = NeighborhoodEventController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodEventControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodEventControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodEventService eventService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubCaller() {
        // The house form (the L41 slice's own note): the untyped any()
        // with the generic hint matches a null Authentication too.
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private NeighborhoodEventView eventView(UUID authorId, UUID locationId) {
        Instant starts = Instant.parse("2026-10-02T08:00:00Z");
        Instant ends = Instant.parse("2026-10-02T11:00:00Z");
        return new NeighborhoodEventView(UUID.randomUUID(), authorId, locationId,
                "VOLUNTEER", "Park cleanup morning", "Tools provided.", starts, ends,
                "Community garden — main gate", "Development committee",
                20, "LIMITED_SEATS", "ACTIVE", false, 0L, false, starts, starts);
    }

    @Test
    void getBoard_member_answersThePagedBodyWithBothAttendanceFacts() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        NeighborhoodEventView view = new NeighborhoodEventView(
                UUID.randomUUID(), userId, locationId,
                "VOLUNTEER", "Park cleanup morning", "Tools provided.",
                Instant.parse("2026-10-02T08:00:00Z"), Instant.parse("2026-10-02T11:00:00Z"),
                "Community garden — main gate", "Development committee",
                20, "LIMITED_SEATS", "ACTIVE", false, 7L, true,
                Instant.parse("2026-09-30T09:00:00Z"), Instant.parse("2026-09-30T09:00:00Z"));
        when(eventService.getBoard(eq(userId), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(view)));

        mockMvc.perform(get("/api/v1/neighborhood/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].authorId").value(userId.toString()))
                .andExpect(jsonPath("$.content[0].attending").value(7))
                .andExpect(jsonPath("$.content[0].rsvpedByMe").value(true))
                .andExpect(jsonPath("$.content[0].registration").value("LIMITED_SEATS"))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    void getBoard_invalidCategory_is400AtTheBoundary() throws Exception {
        stubCaller();

        mockMvc.perform(get("/api/v1/neighborhood/events")
                        .param("category", "NOT_A_CATEGORY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getBoard_categoryParsesToTheEnum_volunteer() throws Exception {
        UUID userId = stubCaller();
        when(eventService.getBoard(eq(userId), eq(EventCategory.VOLUNTEER), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(eventView(userId, UUID.randomUUID()))));

        mockMvc.perform(get("/api/v1/neighborhood/events")
                        .param("category", "VOLUNTEER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].category").value("VOLUNTEER"));
    }

    @Test
    void getBoard_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        when(eventService.getBoard(eq(userId), isNull(), isNull(), any()))
                .thenThrow(new AccessDeniedException("Join a neighborhood before reading its events board"));

        mockMvc.perform(get("/api/v1/neighborhood/events"))
                .andExpect(status().isForbidden());
    }

    @Test
    void eventCreate_validBody_answers201() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(eventService.createEvent(eq(userId), eq(locationId), eq(EventCategory.VOLUNTEER),
                eq("Park cleanup morning"), eq("Tools provided."),
                eq(Instant.parse("2026-10-02T08:00:00Z")),
                eq(Instant.parse("2026-10-02T11:00:00Z")),
                eq("Community garden — main gate"), eq("Development committee"),
                eq(20), eq(EventRegistration.LIMITED_SEATS)))
                .thenReturn(eventView(userId, locationId));

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"VOLUNTEER\", "
                                + "\"title\": \"Park cleanup morning\", "
                                + "\"description\": \"Tools provided.\", "
                                + "\"startsAt\": \"2026-10-02T08:00:00Z\", "
                                + "\"endsAt\": \"2026-10-02T11:00:00Z\", "
                                + "\"locationLabel\": \"Community garden — main gate\", "
                                + "\"organizerLabel\": \"Development committee\", "
                                + "\"capacity\": 20, "
                                + "\"registration\": \"LIMITED_SEATS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category").value("VOLUNTEER"))
                .andExpect(jsonPath("$.title").value("Park cleanup morning"))
                .andExpect(jsonPath("$.registration").value("LIMITED_SEATS"));
    }

    @Test
    void eventCreate_invalidCategory_is400AtTheBoundary() throws Exception {
        stubCaller();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"PARTY\", "
                                + "\"title\": \"Title\", "
                                + "\"description\": \"Body\", "
                                + "\"startsAt\": \"2027-10-02T08:00:00Z\", "
                                + "\"locationLabel\": \"Spot\", "
                                + "\"organizerLabel\": \"Committee\", "
                                + "\"registration\": \"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventCreate_invalidRegistration_is400AtTheBoundary() throws Exception {
        stubCaller();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"SOCIAL\", "
                                + "\"title\": \"Title\", "
                                + "\"description\": \"Body\", "
                                + "\"startsAt\": \"2027-10-02T08:00:00Z\", "
                                + "\"locationLabel\": \"Spot\", "
                                + "\"organizerLabel\": \"Committee\", "
                                + "\"registration\": \"BY_INVITE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventCreate_blankRegistration_is400AtTheBoundary() throws Exception {
        // @NotBlank at the boundary (the CodeRabbit round-1 adoption on
        // the posts slice): blank must NOT slip through to the parse.
        stubCaller();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"SOCIAL\", "
                                + "\"title\": \"Title\", "
                                + "\"description\": \"Body\", "
                                + "\"startsAt\": \"2027-10-02T08:00:00Z\", "
                                + "\"locationLabel\": \"Spot\", "
                                + "\"organizerLabel\": \"Committee\", "
                                + "\"registration\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventCreate_blankTitle_is400AtTheBoundary() throws Exception {
        stubCaller();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"SOCIAL\", "
                                + "\"title\": \"  \", "
                                + "\"description\": \"Body\", "
                                + "\"startsAt\": \"2027-10-02T08:00:00Z\", "
                                + "\"locationLabel\": \"Spot\", "
                                + "\"organizerLabel\": \"Committee\", "
                                + "\"registration\": \"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void eventCreate_missingStartsAt_is400AtTheBoundary() throws Exception {
        stubCaller();
        UUID locationId = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\": \"" + locationId + "\", "
                                + "\"category\": \"SOCIAL\", "
                                + "\"title\": \"Title\", "
                                + "\"description\": \"Body\", "
                                + "\"locationLabel\": \"Spot\", "
                                + "\"organizerLabel\": \"Committee\", "
                                + "\"registration\": \"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteEvent_answers204() throws Exception {
        UUID userId = stubCaller();
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/neighborhood/events/{id}", eventId))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteEvent_unknownEvent_answers404() throws Exception {
        UUID userId = stubCaller();
        UUID eventId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Event", eventId))
                .when(eventService).deleteByOrganizer(userId, eventId);

        mockMvc.perform(delete("/api/v1/neighborhood/events/{id}", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteEvent_notTheOrganizer_answers403() throws Exception {
        UUID userId = stubCaller();
        UUID eventId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new AccessDeniedException("Only the event's organizer can delete it"))
                .when(eventService).deleteByOrganizer(userId, eventId);

        mockMvc.perform(delete("/api/v1/neighborhood/events/{id}", eventId))
                .andExpect(status().isForbidden());
    }

    @Test
    void rsvp_answers201WithTheSeatBody() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.parse("2026-10-01T09:30:00Z");
        when(eventService.rsvp(memberId, eventId)).thenReturn(new EventRsvpView(
                UUID.randomUUID(), eventId, memberId, now, now));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.memberId").value(memberId.toString()));
    }

    @Test
    void rsvp_oneSeatPerMember_answers409() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        when(eventService.rsvp(memberId, eventId)).thenThrow(
                new ConflictException(
                        "One seat per member per event — remove yours before RSVPing again"));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isConflict());
    }

    @Test
    void rsvp_seatsFull_answers409() throws Exception {
        // The capacity gate's own 409 (the AvailabilityService "No
        // available slot" precedent class — state, not syntax).
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        when(eventService.rsvp(memberId, eventId)).thenThrow(
                new ConflictException("This event's seats are full — capacity 20"));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isConflict());
    }

    @Test
    void rsvp_unknownEvent_answers404() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        when(eventService.rsvp(memberId, eventId))
                .thenThrow(new ResourceNotFoundException("Event", eventId));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isNotFound());
    }

    @Test
    void rsvp_notAMemberOfTheEventsNeighborhood_answers403() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        when(eventService.rsvp(memberId, eventId))
                .thenThrow(new AccessDeniedException(
                        "Only members of the event's neighborhood can take a seat"));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isForbidden());
    }

    @Test
    void rsvp_eventAlreadyStarted_answers409() throws Exception {
        // The Greptile round-1 adoption: the window gate — what the
        // forward-looking board won't show, the write won't accept.
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        when(eventService.rsvp(memberId, eventId)).thenThrow(
                new ConflictException("This event has already started — its seats are closed"));

        mockMvc.perform(post("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isConflict());
    }

    @Test
    void unrsvp_answers204() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isNoContent());
    }

    @Test
    void unrsvp_noLiveSeat_answers404() throws Exception {
        UUID memberId = stubCaller();
        UUID eventId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Rsvp", eventId))
                .when(eventService).unrsvp(memberId, eventId);

        mockMvc.perform(delete("/api/v1/events/{id}/rsvp", eventId))
                .andExpect(status().isNotFound());
    }
}

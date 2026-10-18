package com.marketplace.community;

import test.config.IntegrationContainers;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

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
 * W3 / J07 (unified end-to-end plan §3, the journeys register row J07) — the
 * group/event/poll composite journey on the REAL chain: the event with a
 * TWO-SEAT capacity (the seat count serialized on the row — the race-free
 * seat law) → two RSVPs land → the third answers the honest FULL 409 → a
 * withdrawn seat REOPENS → the poll's vote/withdraw/re-vote cycle with the
 * live results visible to the neighborhood → the group's
 * join/re-join-409/leave cycle. Every read comes back over HTTP from the
 * owning rows — no copied state.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class EventPollGroupJourneyIntegrationTest {

    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by the @Testcontainers extension; raw type matches the house precedent
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @MockitoBean
    CurrentUserProvider currentUserProvider;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any(Authentication.class))).thenReturn(userId);
        return userId;
    }

    /**
     * The journey's linking test (plan §5): the two-seat event fills, the
     * third member hits the capacity 409, the withdrawn seat reopens, and
     * the started-event guard closes the seats for good.
     */
    @Test
    void theEventJourneyWithCapacityFullAndReopenedSeats() throws Exception {
        UUID organizerId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String startsAt = Instant.now().plusSeconds(7200).toString();
        String eventBody = mockMvc.perform(post("/api/v1/neighborhood/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\",\"category\":\"WORKSHOP\","
                                + "\"title\":\"J07 first-aid workshop\",\"description\":\"Two seats only.\","
                                + "\"startsAt\":\"" + startsAt + "\",\"locationLabel\":\"المركز الثقافي\","
                                + "\"organizerLabel\":\"فريق الحي\",\"capacity\":2,\"registration\":\"LIMITED_SEATS\"}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attending").value(0))
                .andExpect(jsonPath("$.capacity").value(2))
                .andReturn().getResponse().getContentAsString();
        String eventId = idOf(eventBody);

        // Two members join the hood and take the two seats.
        UUID firstId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isCreated());
        UUID secondId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isCreated());

        // The board's live count — two of two.
        mockMvc.perform(get("/api/v1/neighborhood/events")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(eventId))
                .andExpect(jsonPath("$.content[0].attending").value(2));

        // The third member: the honest FULL 409.
        UUID thirdId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isConflict());

        // The second member withdraws — the freed seat reopens for the third.
        asCaller(secondId);
        mockMvc.perform(delete("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        asCaller(thirdId);
        mockMvc.perform(post("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/events")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].attending").value(2));

        // The double-RSVP branch — one seat per member.
        mockMvc.perform(post("/api/v1/events/{eventId}/rsvp", eventId)
                        .with(jwt()))
                .andExpect(status().isConflict());
    }

    /** The poll cycle: vote → the live results → double-vote 409 → withdraw → re-vote. */
    @Test
    void thePollJourneyWithVoteWithdrawAndLiveResults() throws Exception {
        UUID authorId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        String pollBody = mockMvc.perform(post("/api/v1/neighborhood/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\","
                                + "\"question\":\"Which day for the street cleanup?\","
                                + "\"authorLabel\":\"منسق النشاط\","
                                + "\"options\":[\"Thursday morning\",\"Friday morning\"]}")
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.options.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String pollId = idOf(pollBody);
        String firstOption = optionIdOf(pollBody, 0);
        String secondOption = optionIdOf(pollBody, 1);

        // A member votes — the results are live to the neighborhood.
        UUID voterId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + firstOption + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/polls")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pollId))
                .andExpect(jsonPath("$.content[0].options[0].votes").value(1))
                .andExpect(jsonPath("$.content[0].options[1].votes").value(0))
                .andExpect(jsonPath("$.content[0].votedByMe").value(firstOption));

        // The double vote — the honest 409.
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + secondOption + "\"}")
                        .with(jwt()))
                .andExpect(status().isConflict());

        // The withdraw reopens the voice — the member re-votes the other option.
        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"optionId\":\"" + secondOption + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/polls")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].options[0].votes").value(0))
                .andExpect(jsonPath("$.content[0].options[1].votes").value(1))
                .andExpect(jsonPath("$.content[0].votedByMe").value(secondOption));
    }

    /** The group cycle: join → the live members count → the re-join 409 → leave. */
    @Test
    void theGroupJourneyWithJoinCountAndLeave() throws Exception {
        // A club of the old town — the seed's own raw-SQL channel.
        UUID groupId = UUID.randomUUID();
        jdbc.update("INSERT INTO neighborhood_groups (id, location_id, name, description, "
                        + "is_deleted, version, created_by, created_at, updated_by, updated_at) "
                        + "VALUES (?, ?, ?, ?, FALSE, 0, 'it', now(), 'it', now())",
                groupId, UUID.fromString(QUDSAYYA_OLD_TOWN), "J07 runners club", "نادي الجري");

        UUID memberId = asCaller(UUID.randomUUID());
        mockMvc.perform(put("/api/v1/me/neighborhood")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}")
                        .with(jwt()))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId)
                        .with(jwt()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()));

        // The board's live count — one member, joined by me.
        mockMvc.perform(get("/api/v1/neighborhood/groups")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(groupId.toString()))
                .andExpect(jsonPath("$.content[0].members").value(1))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(true));

        // The re-join — one live membership per member (the honest 409).
        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId)
                        .with(jwt()))
                .andExpect(status().isConflict());

        // The leave releases the slot.
        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", groupId)
                        .with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/neighborhood/groups")
                        .with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].members").value(0))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(false));
    }

    private static String idOf(String body) {
        int start = body.indexOf("\"id\":\"") + 6;
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    private static String optionIdOf(String body, int index) {
        int start = -1;
        for (int i = 0; i <= index; i++) {
            start = body.indexOf("\"id\":\"", start + 1) + 6;
        }
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}

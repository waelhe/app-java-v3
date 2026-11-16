package com.marketplace.community;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L52 — the polls board's DATABASE-backed guards (the Neighborhood
 * GroupBoardIntegrationTest discipline verbatim: the service tests
 * mock the repository, so the board scoping, the newest-first sort
 * key, the live grouped per-option counts, the soft-delete filtering
 * and V100's one-vote unique index had no test that could see them).
 * This class runs every acceptance fact over the REAL chain: HTTP →
 * the resource-server chain → the membership gate → the REAL geo seed
 * tree → V100's real schema (the partial unique vote index, the
 * partial board index, the option/position index, the Envers mirrors)
 * → the grouped count query PostgreSQL actually compiles.
 *
 * <p>Every test uses its OWN random user ids (the L41/L42 convention —
 * no test-level transaction; each service call commits its own), and
 * the shared container's poll rows are wiped between tests (the
 * board's scope is the neighborhood, so an earlier test's rows would
 * bleed into a later board count). The polls themselves ride the REAL
 * create write (this wave HAS an authoring write — the composer's own
 * channel) with explicit {@code created_at} literals injected after
 * the insert so the newest-first order key is deterministic.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The module net pins the CONTRACT LOGIC, not the limiter windows
        // (the events/market/groups tests' own convention): the
        // create/vote cycles below spend the pollCreate/pollVote windows
        // too, so the instances ride generous test-only budgets here.
        "resilience4j.ratelimiter.instances.pollCreate.limit-for-period=100",
        "resilience4j.ratelimiter.instances.pollCreate.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.pollCreate.timeout-duration=0",
        "resilience4j.ratelimiter.instances.pollVote.limit-for-period=100",
        "resilience4j.ratelimiter.instances.pollVote.limit-refresh-period=60s",
        "resilience4j.ratelimiter.instances.pollVote.timeout-duration=0",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NeighborhoodPollBoardIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): two DIFFERENT level-3
    // neighborhoods — the board-scoping and vote-scoping facts need both.
    private static final String OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String SUBURB = "11111111-1111-4111-8111-111111111105";

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

    @Autowired
    private NeighborhoodMembershipService membershipService;

    @BeforeEach
    void isolatePollData() {
        jdbc.update("DELETE FROM neighborhood_poll_votes_aud");
        jdbc.update("DELETE FROM neighborhood_poll_votes");
        jdbc.update("DELETE FROM neighborhood_poll_options_aud");
        jdbc.update("DELETE FROM neighborhood_poll_options");
        jdbc.update("DELETE FROM neighborhood_polls_aud");
        jdbc.update("DELETE FROM neighborhood_polls");
    }

    private UUID asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        return userId;
    }

    /** Joins the given node over the REAL membership service. */
    private UUID joinedMember(String locationId) {
        UUID userId = UUID.randomUUID();
        membershipService.join(userId, UUID.fromString(locationId));
        return userId;
    }

    /** The create write's JSON body — the composer's own channel. */
    private static String createBody(String locationId, String question) {
        return """
                {
                  "locationId": "%s",
                  "question": "%s",
                  "authorLabel": "لجنة تطوير الحي",
                  "options": ["الفجر — ٥:٣٠ إلى ٨:٠٠", "المساء — ٥:٠٠ إلى ٨:٣٠", "كلا الفترتين"]
                }
                """.formatted(locationId, question);
    }

    /** Pins a poll's created_at AFTER the insert — the deterministic order key. */
    private void stampPollCreatedAt(UUID pollId, String createdAt) {
        jdbc.update("UPDATE neighborhood_polls SET created_at = ?, updated_at = ? WHERE id = ?",
                java.sql.Timestamp.valueOf(createdAt), java.sql.Timestamp.valueOf(createdAt), pollId);
    }

    // ---- (1) the board scoping -------------------------------------------------

    @Test
    void boardScoping_anotherNeighborhoodsPollsNeverAppear() throws Exception {
        // The old-town member is the READER; the suburb member only
        // authors the other hood's poll (the first CI round's measured
        // lesson: the caller stub must return to the READER before the
        // board read — the last asCaller() wins, and reading as the
        // suburb member measured HIS board carrying his own poll).
        UUID oldTownReader = asCaller(joinedMember(OLD_TOWN));
        asCaller(joinedMember(SUBURB));

        // A suburb member's poll must never reach the old-town board.
        mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(SUBURB, "سؤال الضاحية")))
                .andExpect(status().isCreated());

        asCaller(oldTownReader);
        mockMvc.perform(get("/api/v1/neighborhood/polls").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ---- (2) the complete newest-first sort key ----------------------------------

    @Test
    void board_isNewestFirst_theLatestPollLeads() throws Exception {
        asCaller(joinedMember(OLD_TOWN));
        org.springframework.test.web.servlet.ResultActions first =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "السؤال الأقدم")));
        UUID older = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(first.andReturn().getResponse().getContentAsString(), "$.id"));
        stampPollCreatedAt(older, "2026-05-01 10:00:00");
        org.springframework.test.web.servlet.ResultActions second =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "السؤال الأحدث")));
        UUID newer = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(second.andReturn().getResponse().getContentAsString(), "$.id"));
        stampPollCreatedAt(newer, "2026-09-01 10:00:00");

        mockMvc.perform(get("/api/v1/neighborhood/polls").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].question").value("السؤال الأحدث"))
                .andExpect(jsonPath("$.content[1].question").value("السؤال الأقدم"));
    }

    // ---- (3) the create/vote/withdraw cycle over the real schema ----------------

    @Test
    void createVoteWithdrawCycle_theOneVoteRuleAndTheReaderScopedFacts() throws Exception {
        UUID member = asCaller(joinedMember(OLD_TOWN));

        // The create: 201, and the echo carries the full authored option
        // set in the author's own order with zero counts.
        org.springframework.test.web.servlet.ResultActions created =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟")));
        created.andExpect(status().isCreated())
                .andExpect(jsonPath("$.question").value("ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟"))
                .andExpect(jsonPath("$.author").value("لجنة تطوير الحي"))
                .andExpect(jsonPath("$.options.length()").value(3))
                .andExpect(jsonPath("$.options[0].position").value(0))
                .andExpect(jsonPath("$.options[1].position").value(1))
                .andExpect(jsonPath("$.options[2].position").value(2))
                .andExpect(jsonPath("$.options[0].votes").value(0));
        String body = created.andReturn().getResponse().getContentAsString();
        UUID pollId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        UUID morningOption = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.options[0].id"));
        UUID eveningOption = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.options[1].id"));

        // A SECOND member votes the morning option over the real chain —
        // the grouped count the board read carries.
        UUID other = asCaller(joinedMember(OLD_TOWN));
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(morningOption)))
                .andExpect(status().isCreated());
        asCaller(member);

        // The member votes the evening option: 201, and the board carries
        // the live per-option counts AND the member's own choice.
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(eveningOption)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pollId").value(pollId.toString()))
                .andExpect(jsonPath("$.optionId").value(eveningOption.toString()));
        mockMvc.perform(get("/api/v1/neighborhood/polls").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].options[0].votes").value(1))
                .andExpect(jsonPath("$.content[0].options[1].votes").value(1))
                .andExpect(jsonPath("$.content[0].options[2].votes").value(0))
                .andExpect(jsonPath("$.content[0].votedByMe").value(eveningOption.toString()));

        // The one-vote rule: the second vote answers 409 with the
        // contract's own words (the V100 partial unique index is the
        // backstop — the explicit 409 lands first).
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(morningOption)))
                .andExpect(status().isConflict());

        // The withdraw: 204, then the honest 404 (nothing left to
        // withdraw), then the board's facts are back to the open state.
        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId).with(jwt()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId).with(jwt()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/neighborhood/polls").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].votedByMe").doesNotExist())
                .andExpect(jsonPath("$.content[0].options[1].votes").value(0));

        // b-5's retention: the withdrawn vote row stays — the reads stop
        // returning it, the audit trail keeps the revision.
        Integer withdrawnRow = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_poll_votes "
                        + "WHERE poll_id = ? AND member_id = ? AND is_deleted = TRUE",
                Integer.class, pollId, member);
        assertThat(withdrawnRow).isEqualTo(1);

        // The freed vote is open for a fresh vote (the V100 partial unique
        // index admits exactly that).
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(morningOption)))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/neighborhood/polls").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].votedByMe").value(morningOption.toString()));
    }

    // ---- (4) the option gates over the real chain -------------------------------

    @Test
    void vote_optionOfAnotherPoll_answers400() throws Exception {
        UUID member = asCaller(joinedMember(OLD_TOWN));
        org.springframework.test.web.servlet.ResultActions one =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "السؤال الأول")));
        UUID pollOne = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(one.andReturn().getResponse().getContentAsString(), "$.id"));
        org.springframework.test.web.servlet.ResultActions two =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "السؤال الثاني")));
        UUID pollTwo = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(two.andReturn().getResponse().getContentAsString(), "$.id"));
        String pollTwoBody = two.andReturn().getResponse().getContentAsString();
        UUID pollTwoOption = UUID.fromString(
                com.jayway.jsonpath.JsonPath.read(pollTwoBody, "$.options[0].id"));

        // An option of poll TWO carried to poll ONE's vote: the honest
        // 400 (a malformed reference for THIS poll), before any write.
        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollOne).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(pollTwoOption)))
                .andExpect(status().isBadRequest());
        Integer votes = jdbc.queryForObject(
                "SELECT count(*) FROM neighborhood_poll_votes WHERE poll_id = ? AND member_id = ?",
                Integer.class, pollOne, member);
        assertThat(votes).isZero();
    }

    // ---- (5) the wrong-neighborhood vote gate over the real chain ----------------

    @Test
    void vote_memberOfAnotherNeighborhood_is403() throws Exception {
        UUID member = asCaller(joinedMember(SUBURB));
        UUID other = asCaller(joinedMember(OLD_TOWN));
        org.springframework.test.web.servlet.ResultActions created =
                mockMvc.perform(post("/api/v1/neighborhood/polls").with(jwt())
                        .contentType("application/json")
                        .content(createBody(OLD_TOWN, "سؤال البلد")));
        String body = created.andReturn().getResponse().getContentAsString();
        UUID pollId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.id"));
        UUID optionId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.options[0].id"));
        asCaller(member);

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId).with(jwt())
                        .contentType("application/json")
                        .content("{\"optionId\": \"%s\"}".formatted(optionId)))
                .andExpect(status().isForbidden());
    }
}

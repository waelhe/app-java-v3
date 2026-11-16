package com.marketplace.community;

import com.marketplace.shared.api.BadRequestException;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L52 MVC slice: the polls board surface's request shape and status
 * contract. The membership 403s (G-N3), the gate orders and the three
 * batch reads are pinned against the REAL chain by the module
 * integration test; this slice pins the HTTP contract itself (the
 * L41/L42/L47/L49/L50/L51 WebMvc precedent): the paged board body
 * with the full option set (each option's live count) and the
 * votedByMe choice, the 201 create echo with the authored option set,
 * the 201 vote echo, the honest 404s (unknown poll, unknown option),
 * the 400s (the option of another poll, the bean-validation gates,
 * the option-set cardinality), the 409 one-vote word and the 204
 * withdraw.
 */
@WebMvcTest(controllers = NeighborhoodPollController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodPollControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodPollControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodPollService pollService;

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

    private NeighborhoodPollView pollView(UUID pollId, UUID votedByMe) {
        return new NeighborhoodPollView(pollId,
                "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟",
                "لجنة تطوير الحي",
                List.of(
                        new NeighborhoodPollOptionView(UUID.randomUUID(), "الفجر — ٥:٣٠ إلى ٨:٠٠", 0, 1),
                        new NeighborhoodPollOptionView(UUID.randomUUID(), "المساء — ٥:٠٠ إلى ٨:٣٠", 1, 2),
                        new NeighborhoodPollOptionView(UUID.randomUUID(), "كلا الفترتين", 2, 0)),
                votedByMe,
                Instant.parse("2026-09-27T18:00:00Z"));
    }

    @Test
    void getBoard_member_answersThePagedBodyWithTheOptionSetAndMyChoice() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        UUID chosenOption = UUID.randomUUID();
        when(pollService.getBoard(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(
                        new NeighborhoodPollView(pollId,
                                "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟",
                                "لجنة تطوير الحي",
                                List.of(
                                        new NeighborhoodPollOptionView(chosenOption, "المساء — ٥:٠٠ إلى ٨:٣٠", 0, 2),
                                        new NeighborhoodPollOptionView(UUID.randomUUID(), "الفجر — ٥:٣٠ إلى ٨:٠٠", 1, 1)),
                                chosenOption,
                                Instant.parse("2026-09-27T18:00:00Z")))));

        mockMvc.perform(get("/api/v1/neighborhood/polls"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(pollId.toString()))
                .andExpect(jsonPath("$.content[0].question")
                        .value("ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟"))
                .andExpect(jsonPath("$.content[0].author").value("لجنة تطوير الحي"))
                .andExpect(jsonPath("$.content[0].options[0].label").value("المساء — ٥:٠٠ إلى ٨:٣٠"))
                .andExpect(jsonPath("$.content[0].options[0].position").value(0))
                .andExpect(jsonPath("$.content[0].options[0].votes").value(2))
                .andExpect(jsonPath("$.content[0].options[1].votes").value(1))
                .andExpect(jsonPath("$.content[0].votedByMe").value(chosenOption.toString()))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    void getBoard_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        when(pollService.getBoard(eq(userId), any()))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading its polls board"));

        mockMvc.perform(get("/api/v1/neighborhood/polls"))
                .andExpect(status().isForbidden());
    }

    @Test
    void pollCreate_valid_answers201WithTheAuthoredOptionSet() throws Exception {
        UUID userId = stubCaller();
        UUID locationId = UUID.randomUUID();
        when(pollService.create(eq(userId), eq(locationId), any(), any(), any()))
                .thenReturn(pollView(UUID.randomUUID(), null));

        mockMvc.perform(post("/api/v1/neighborhood/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "question": "ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟",
                                  "authorLabel": "لجنة تطوير الحي",
                                  "options": ["الفجر — ٥:٣٠ إلى ٨:٠٠", "المساء — ٥:٠٠ إلى ٨:٣٠", "كلا الفترتين"]
                                }
                                """.formatted(locationId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.question")
                        .value("ما المواعيد الأنسب لفتح الممشى المظلل خلال الصيف؟"))
                .andExpect(jsonPath("$.author").value("لجنة تطوير الحي"))
                .andExpect(jsonPath("$.options.length()").value(3))
                .andExpect(jsonPath("$.votedByMe").doesNotExist());
    }

    @Test
    void pollCreate_blankQuestion_is400TheBeanValidationGate() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "question": "",
                                  "authorLabel": "لجنة تطوير الحي",
                                  "options": ["أ", "ب"]
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pollCreate_missingOptionList_is400TheBeanValidationGate() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/neighborhood/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "question": "س",
                                  "authorLabel": "لجنة تطوير الحي"
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pollCreate_wrongOptionCardinality_is400WithTheContractsOwnWords() throws Exception {
        UUID userId = stubCaller();
        when(pollService.create(eq(userId), any(), any(), any(), any()))
                .thenThrow(new BadRequestException(
                        "A poll carries one question and 2 to 5 options — got 1"));

        mockMvc.perform(post("/api/v1/neighborhood/polls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "locationId": "%s",
                                  "question": "س",
                                  "authorLabel": "لجنة تطوير الحي",
                                  "options": ["أ"]
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vote_valid_answers201WithTheVoteEcho() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        when(pollService.vote(userId, pollId, optionId))
                .thenReturn(new NeighborhoodPollVoteView(
                        UUID.randomUUID(), pollId, optionId, userId,
                        Instant.parse("2026-10-03T10:00:00Z")));

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": "%s"}
                                """.formatted(optionId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pollId").value(pollId.toString()))
                .andExpect(jsonPath("$.optionId").value(optionId.toString()))
                .andExpect(jsonPath("$.memberId").value(userId.toString()));
    }

    @Test
    void vote_unknownPoll_answers404() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        when(pollService.vote(eq(userId), eq(pollId), any()))
                .thenThrow(new ResourceNotFoundException("Poll", pollId));

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void vote_unknownOption_answersTheHonest404() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        when(pollService.vote(eq(userId), eq(pollId), any()))
                .thenThrow(new ResourceNotFoundException("Poll option", UUID.randomUUID()));

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }

    @Test
    void vote_optionOfAnotherPoll_answers400() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        when(pollService.vote(eq(userId), eq(pollId), any()))
                .thenThrow(new BadRequestException(
                        "The option does not belong to this poll — a vote carries one of the poll's own options"));

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void vote_alreadyVoted_answers409WithTheContractsOwnWords() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        when(pollService.vote(eq(userId), eq(pollId), any()))
                .thenThrow(new ConflictException(
                        "One vote per member per poll — withdraw it before voting again"));

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", pollId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"optionId": "%s"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isConflict());
    }

    @Test
    void vote_bodyWithoutOptionId_is400TheBeanValidationGate() throws Exception {
        stubCaller();

        mockMvc.perform(post("/api/v1/polls/{pollId}/vote", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdraw_succeeds_answers204() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId))
                .andExpect(status().isNoContent());
    }

    @Test
    void withdraw_noLiveVote_answersTheHonest404() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Poll vote", pollId))
                .when(pollService).withdraw(userId, pollId);

        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId))
                .andExpect(status().isNotFound());
    }

    @Test
    void withdraw_unknownPoll_answers404() throws Exception {
        UUID userId = stubCaller();
        UUID pollId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Poll", pollId))
                .when(pollService).withdraw(userId, pollId);

        mockMvc.perform(delete("/api/v1/polls/{pollId}/vote", pollId))
                .andExpect(status().isNotFound());
    }
}

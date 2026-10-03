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
 * L51 MVC slice: the groups board surface's request shape and status
 * contract. The membership 403s (G-N3), the gate orders and the
 * grouped count read are pinned against the REAL chain by the module
 * integration test; this slice pins the HTTP contract itself (the
 * L41/L42/L47/L49/L50 WebMvc precedent): the paged board body with
 * the two reader-scoped facts, the honest 404s, the 409 one-membership
 * word, the 201 join echo and the 204 leave. No type gates ride this
 * surface (the registered contract carries no enumerated vocabulary —
 * the group is name/description/members), so there is no 400-before-
 * any-write case to pin: the path's own UUID is the only parameter.
 */
@WebMvcTest(controllers = NeighborhoodGroupController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodGroupControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodGroupControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodGroupService groupService;

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

    private NeighborhoodGroupView groupView(UUID groupId, long members, boolean joinedByMe) {
        return new NeighborhoodGroupView(groupId, "فريق دراجي ومشي النخيل",
                "تجمّع يومي 5:30 فجراً", members, joinedByMe);
    }

    @Test
    void getBoard_member_answersThePagedBodyWithBothReaderScopedFacts() throws Exception {
        UUID userId = stubCaller();
        UUID joinedId = UUID.randomUUID();
        when(groupService.getBoard(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(
                        groupView(joinedId, 7, true),
                        groupView(UUID.randomUUID(), 1, false))));

        mockMvc.perform(get("/api/v1/neighborhood/groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(joinedId.toString()))
                .andExpect(jsonPath("$.content[0].name").value("فريق دراجي ومشي النخيل"))
                .andExpect(jsonPath("$.content[0].description").value("تجمّع يومي 5:30 فجراً"))
                .andExpect(jsonPath("$.content[0].members").value(7))
                .andExpect(jsonPath("$.content[0].joinedByMe").value(true))
                .andExpect(jsonPath("$.content[1].members").value(1))
                .andExpect(jsonPath("$.content[1].joinedByMe").value(false))
                .andExpect(jsonPath("$.pageNumber").value(0));
    }

    @Test
    void getBoard_noMembership_answers403ProblemDetail() throws Exception {
        UUID userId = stubCaller();
        when(groupService.getBoard(eq(userId), any()))
                .thenThrow(new AccessDeniedException(
                        "Join a neighborhood before reading its groups board"));

        mockMvc.perform(get("/api/v1/neighborhood/groups"))
                .andExpect(status().isForbidden());
    }

    @Test
    void join_valid_answers201WithTheMembershipEcho() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-02T10:30:00Z");
        when(groupService.join(userId, groupId))
                .thenReturn(new NeighborhoodGroupMembershipView(
                        UUID.randomUUID(), groupId, userId, at, at));

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.groupId").value(groupId.toString()))
                .andExpect(jsonPath("$.memberId").value(userId.toString()));
    }

    @Test
    void join_unknownGroup_answers404() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();
        when(groupService.join(userId, groupId))
                .thenThrow(new ResourceNotFoundException("Group", groupId));

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isNotFound());
    }

    @Test
    void join_alreadyAMember_answers409WithTheContractsOwnWords() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();
        when(groupService.join(userId, groupId))
                .thenThrow(new ConflictException(
                        "One membership per member per group — leave before joining again"));

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isConflict());
    }

    @Test
    void join_notAMemberOfTheGroupsNeighborhood_answers403() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();
        when(groupService.join(userId, groupId))
                .thenThrow(new AccessDeniedException(
                        "Only members of the group's neighborhood can join it"));

        mockMvc.perform(post("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isForbidden());
    }

    @Test
    void leave_succeeds_answers204() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isNoContent());
    }

    @Test
    void leave_noLiveMembership_answersTheHonest404() throws Exception {
        UUID userId = stubCaller();
        UUID groupId = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ResourceNotFoundException("Group membership", groupId))
                .when(groupService).leave(userId, groupId);

        mockMvc.perform(delete("/api/v1/neighborhood/groups/{groupId}/membership", groupId))
                .andExpect(status().isNotFound());
    }
}

package com.marketplace.community;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The verification lifecycle's MVC slice: the administrative surface's
 * request validation and method-security shape (the ModerationAdminController
 * pattern verbatim — the L45 precedent). The full lifecycle is pinned
 * against the REAL chain by the module integration test (the transitions,
 * the 409s, the Envers trail); this slice pins the controller leg itself:
 * the class-level ADMIN gate (403 for a plain authenticated user, 200 for
 * an ADMIN), the state axis's 400 before any read, the decision type
 * gate's 400, and the delegation shapes.
 */
@WebMvcTest(controllers = NeighborhoodVerificationAdminController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(NeighborhoodVerificationAdminControllerWebMvcTest.MethodSecurityConfig.class)
class NeighborhoodVerificationAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NeighborhoodMembershipService membershipService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    /** The method-security leg needs its own enablement in the slice. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private NeighborhoodMembershipView view(UUID membershipId, String state) {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        return new NeighborhoodMembershipView(membershipId, UUID.randomUUID(),
                UUID.randomUUID(), state, now, now, now);
    }

    @Test
    void plainUser_onBothEndpoints_is403() throws Exception {
        // The class-level gate (the L30 pattern's controller leg): a plain
        // authenticated user never reaches either handler.
        mockMvc.perform(get("/api/v1/admin/neighborhood-memberships"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification",
                        UUID.randomUUID()).queryParam("decision", "APPROVE"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_queueRead_answers200_bothAxes() throws Exception {
        when(membershipService.getVerificationQueue(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/neighborhood-memberships"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/neighborhood-memberships")
                        .queryParam("state", "PENDING"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_queueRead_carriesTheRowAndTheDelegation() throws Exception {
        UUID membershipId = UUID.randomUUID();
        when(membershipService.getVerificationQueue(
                eq(MembershipVerificationState.PENDING), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(view(membershipId, "PENDING"))));

        mockMvc.perform(get("/api/v1/admin/neighborhood-memberships")
                        .queryParam("state", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(membershipId.toString()))
                .andExpect(jsonPath("$.content[0].verificationState").value("PENDING"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_invalidStateFilter_isTheTypeGate400() throws Exception {
        mockMvc.perform(get("/api/v1/admin/neighborhood-memberships")
                        .queryParam("state", "WAITING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_review_answers200WithTheDecision() throws Exception {
        UUID membershipId = UUID.randomUUID();
        when(membershipService.reviewVerification(membershipId, true))
                .thenReturn(view(membershipId, "VERIFIED"));

        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification",
                        membershipId).queryParam("decision", "APPROVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("VERIFIED"));
        verify(membershipService).reviewVerification(membershipId, true);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_invalidDecision_isTheTypeGate400() throws Exception {
        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification",
                        UUID.randomUUID()).queryParam("decision", "MAYBE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_review_passthroughMediaType() throws Exception {
        // The review endpoint answers the view JSON (not a 406 from a
        // mismatched content negotiation) — the L45 resolve leg's own
        // guard against the silent Accept-header trap.
        UUID membershipId = UUID.randomUUID();
        when(membershipService.reviewVerification(membershipId, false))
                .thenReturn(view(membershipId, "REJECTED"));

        mockMvc.perform(post("/api/v1/admin/neighborhood-memberships/{id}/verification",
                        membershipId).queryParam("decision", "REJECT")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("REJECTED"));
    }
}

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L45 MVC slice: the administrative moderation surface's request
 * validation and method-security shape. The chain's own
 * {@code /api/v1/admin/**} rule and the full resolve atomicity are
 * pinned against the REAL chain by the module integration test (the
 * plan's criterion 4 negative lives there too); this slice pins the
 * controller leg itself (the L41/L42 WebMvc precedent + the
 * GeoAdminController method-security shape): the class-level ADMIN gate
 * (403 for a plain authenticated user, 200 for an ADMIN), the action
 * type gate's 400, the note bound, and the queue read's delegation.
 */
@WebMvcTest(controllers = ModerationAdminController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
@Import(ModerationAdminControllerWebMvcTest.MethodSecurityConfig.class)
class ModerationAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentReportService reportService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    /** The method-security leg needs its own enablement in the slice. */
    @org.springframework.boot.test.context.TestConfiguration
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private UUID stubCaller() {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private ContentReportView resolvedView(UUID reportId) {
        Instant now = Instant.parse("2026-09-18T10:15:00Z");
        return new ContentReportView(reportId, UUID.randomUUID(), "POST", UUID.randomUUID(),
                "SPAM", "RESOLVED", "Spam confirmed", UUID.randomUUID(), now, now, now);
    }

    @Test
    void plainUser_onBothEndpoints_is403() throws Exception {
        stubCaller();
        // The class-level gate (the L30 pattern's controller leg): a
        // plain authenticated user never reaches either handler.
        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"DISMISS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_queueRead_answers200() throws Exception {
        stubCaller();
        when(reportService.getReports(any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/reports"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/reports").queryParam("status", "OPEN"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_invalidStatusFilter_isTheTypeGate400() throws Exception {
        stubCaller();
        mockMvc.perform(get("/api/v1/admin/reports").queryParam("status", "DONE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_resolve_answers200WithTheClosedReport() throws Exception {
        stubCaller();
        UUID reportId = UUID.randomUUID();
        when(reportService.resolveReport(any(), any(), any(), any()))
                .thenReturn(resolvedView(reportId));

        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", reportId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"HIDE_CONTENT\", \"note\": \"Spam confirmed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionNote").value("Spam confirmed"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_invalidAction_isTheTypeGate400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"DELETE\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_overLimitNote_is400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"DISMISS\", \"note\": \"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void admin_blankAction_is400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/admin/reports/{id}/resolve", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"  \"}"))
                .andExpect(status().isBadRequest());
    }
}

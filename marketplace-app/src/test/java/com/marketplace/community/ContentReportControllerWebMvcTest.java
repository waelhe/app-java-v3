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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L45 MVC slice: the report intake's request validation and status
 * shape. The gate orders, the queue and the event publication are
 * pinned against the REAL chain by the module integration test; this
 * slice pins the HTTP contract itself (the L41/L42 WebMvc precedent):
 * the bean-validation 400s (blank targetType/reason, absent targetId —
 * BEFORE any write), the type gate's own 400s (invalid enum values,
 * never an enum-binding 500), and the 201 write.
 */
@WebMvcTest(controllers = ContentReportController.class,
        excludeAutoConfiguration = {
                OAuth2ResourceServerAutoConfiguration.class
        })
@WithMockUser
class ContentReportControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContentReportService reportService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    private UUID stubCaller() {
        UUID userId = UUID.randomUUID();
        when(currentUserProvider.getCurrentUserId(
                org.mockito.ArgumentMatchers.<org.springframework.security.core.Authentication>any()))
                .thenReturn(userId);
        return userId;
    }

    private ContentReportView reportView(UUID reporterId, UUID targetId) {
        Instant now = Instant.parse("2026-09-18T10:00:00Z");
        return new ContentReportView(UUID.randomUUID(), reporterId, "POST", targetId,
                "SPAM", "OPEN", null, null, null, now, now);
    }

    @Test
    void create_answers201() throws Exception {
        UUID reporterId = stubCaller();
        UUID targetId = UUID.randomUUID();
        when(reportService.createReport(any(), any(), any(), any()))
                .thenReturn(reportView(reporterId, targetId));

        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"POST\", \"targetId\": \"" + targetId
                                + "\", \"reason\": \"SPAM\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.targetType").value("POST"));
    }

    @Test
    void blankTargetType_is400BeforeAnyWrite() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"  \", \"targetId\": \""
                                + UUID.randomUUID() + "\", \"reason\": \"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void absentTargetId_is400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"POST\", \"reason\": \"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void blankReason_is400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"POST\", \"targetId\": \""
                                + UUID.randomUUID() + "\", \"reason\": \"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTargetType_isTheTypeGate400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"LISTING\", \"targetId\": \""
                                + UUID.randomUUID() + "\", \"reason\": \"SPAM\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidReason_isTheTypeGate400() throws Exception {
        stubCaller();
        mockMvc.perform(post("/api/v1/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\": \"POST\", \"targetId\": \""
                                + UUID.randomUUID() + "\", \"reason\": \"RUDENESS\"}"))
                .andExpect(status().isBadRequest());
    }
}

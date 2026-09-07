package com.marketplace.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = NotificationController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class NotificationControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService service;

    /** L22: the preferences endpoints' collaborator. */
    @MockitoBean
    private NotificationPreferenceService preferenceService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser
    void getMine_returnsOk() throws Exception {
        when(service.getMyNotifications(any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void markRead_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.markAsRead(any(), any())).thenReturn(org.mockito.Mockito.mock(Notification.class));

        mockMvc.perform(post("/api/v1/notifications/{id}/read", id))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser
    void getMyPreferences_returnsMatrix() throws Exception {
        when(preferenceService.getMyPreferences(any())).thenReturn(List.of(
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false)));

        mockMvc.perform(get("/api/v1/notifications/preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("PAYMENT_STATE"))
                .andExpect(jsonPath("$[0].channel").value("EMAIL"))
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    @WithMockUser
    void updateMyPreferences_returnsMatrix() throws Exception {
        when(preferenceService.updateMyPreferences(any(), any())).thenReturn(List.of(
                new NotificationPreferenceView(NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL, false)));

        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preferences": [
                                  {"type": "PAYMENT_STATE", "channel": "EMAIL", "enabled": false}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].enabled").value(false));
    }

    @Test
    @WithMockUser
    void updateMyPreferences_rejectsMissingChannel() throws Exception {
        // Framework validation (jakarta @NotNull on the switch entry) — the
        // GlobalExceptionHandler answers 400, never a 500.
        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preferences": [
                                  {"type": "PAYMENT_STATE", "enabled": false}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void updateMyPreferences_rejectsEmptyList() throws Exception {
        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preferences": []}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void updateMyPreferences_rejectsUnknownEnumValue() throws Exception {
        // Jackson binding: an unknown type/channel is a malformed request
        // body — 400, not 500.
        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preferences": [
                                  {"type": "NOT_A_TYPE", "channel": "EMAIL", "enabled": false}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    void updateMyPreferences_rejectsInAppChannelOptOut() throws Exception {
        // Binding-layer validation (the record's @AssertTrue): the DB
        // channel is always on — the framework answers 400 before any
        // controller code runs (the service guard is the defense-in-depth
        // copy; the WebMvc slice proves the framework layer).
        mockMvc.perform(put("/api/v1/notifications/preferences")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preferences": [
                                  {"type": "PAYMENT_STATE", "channel": "DB", "enabled": false}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }
}

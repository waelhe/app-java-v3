package com.marketplace.provider;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProviderStatsController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class ProviderStatsControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProviderStatsService statsService;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    @MockitoBean
    private com.marketplace.shared.api.ProviderLookupPort providerLookupPort;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    private static final java.util.UUID USER_ID = java.util.UUID.randomUUID();
    private static final java.util.UUID PROVIDER_ID = java.util.UUID.randomUUID();

    @Test
    @WithMockUser
    void noWindowParams_defaultsToTheLastThirtyDays() throws Exception {
        stubOwnProfile();
        when(statsService.getStats(eq(USER_ID), any())).thenReturn(new ProviderStatsResponse(
                java.time.Instant.parse("2026-09-01T00:00:00Z"),
                java.time.Instant.parse("2026-10-01T00:00:00Z"),
                0.4, 4500L, 3L));

        mockMvc.perform(get("/api/v1/providers/me/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.occupancyRate").value(0.4))
                .andExpect(jsonPath("$.netRevenueCents").value(4500))
                .andExpect(jsonPath("$.completedBookings").value(3));

        ArgumentCaptor<StatsWindow> window = ArgumentCaptor.forClass(StatsWindow.class);
        verify(statsService).getStats(eq(USER_ID), window.capture());
        java.time.Duration span = java.time.Duration.between(window.getValue().from(), window.getValue().to());
        assertThat(span).isEqualTo(java.time.Duration.ofDays(30));
    }

    @Test
    @WithMockUser
    void explicitWindow_reachesTheService_verbatim() throws Exception {
        stubOwnProfile();
        when(statsService.getStats(eq(USER_ID), any())).thenReturn(new ProviderStatsResponse(
                java.time.Instant.parse("2026-09-05T00:00:00Z"),
                java.time.Instant.parse("2026-09-15T00:00:00Z"),
                0.5, 0L, 0L));

        mockMvc.perform(get("/api/v1/providers/me/stats")
                        .param("from", "2026-09-05T00:00:00Z")
                        .param("to", "2026-09-15T00:00:00Z"))
                .andExpect(status().isOk());

        ArgumentCaptor<StatsWindow> window = ArgumentCaptor.forClass(StatsWindow.class);
        verify(statsService).getStats(eq(USER_ID), window.capture());
        assertThat(window.getValue().from()).isEqualTo(java.time.Instant.parse("2026-09-05T00:00:00Z"));
        assertThat(window.getValue().to()).isEqualTo(java.time.Instant.parse("2026-09-15T00:00:00Z"));
    }

    @Test
    @WithMockUser
    void noProviderProfile_isA404_notA500() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(providerLookupPort.findByUserId(USER_ID)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/providers/me/stats"))
                .andExpect(status().isNotFound());

        verify(statsService, never()).getStats(any(), any());
    }

    @Test
    @WithMockUser
    void oneBoundOnly_isA400BeforeAnyQuery() throws Exception {
        mockMvc.perform(get("/api/v1/providers/me/stats")
                        .param("from", "2026-09-05T00:00:00Z"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/providers/me/stats")
                        .param("to", "2026-09-15T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(statsService, never()).getStats(any(), any());
    }

    @Test
    @WithMockUser
    void reversedWindow_isA400() throws Exception {
        mockMvc.perform(get("/api/v1/providers/me/stats")
                        .param("from", "2026-09-15T00:00:00Z")
                        .param("to", "2026-09-05T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(statsService, never()).getStats(any(), any());
    }

    @Test
    @WithMockUser
    void longerThanOneYear_isA400() throws Exception {
        // The maximum is one year (acceptance criterion 3): a 366-day span
        // is out (2025-09-04 -> 2026-09-05, no leap day in between).
        mockMvc.perform(get("/api/v1/providers/me/stats")
                        .param("from", "2025-09-04T00:00:00Z")
                        .param("to", "2026-09-05T00:00:00Z"))
                .andExpect(status().isBadRequest());

        verify(statsService, never()).getStats(any(), any());
    }

    private void stubOwnProfile() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(USER_ID);
        when(providerLookupPort.findByUserId(USER_ID)).thenReturn(java.util.Optional.of(
                new com.marketplace.shared.api.ProviderSummary(PROVIDER_ID, "Stats Provider", "VERIFIED", USER_ID)));
    }
    // NOTE (A1 contract): the controller passes the USER id as the
    // cross-module provider id — the stats service receives USER_ID (the
    // space every provider_id column carries), not the profile id.
}

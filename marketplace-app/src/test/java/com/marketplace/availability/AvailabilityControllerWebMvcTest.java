package com.marketplace.availability;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = AvailabilityController.class,
    excludeAutoConfiguration = {
        OAuth2ClientAutoConfiguration.class,
        OAuth2ClientWebSecurityAutoConfiguration.class,
        OAuth2ResourceServerAutoConfiguration.class
    })
class AvailabilityControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityService availabilityService;

    @MockitoBean
    private AuthHelper authHelper;

    @Test
    void getSlots_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(availabilityService.getSlots(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/providers/{providerId}/availability", providerId)
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-06-30T23:59:59Z"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createSlot_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(availabilityService.createSlot(any(), any(), any())).thenReturn(org.mockito.Mockito.mock(AvailabilitySlot.class));

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/slots", providerId)
                        .param("startsAt", "2026-06-15T10:00:00Z")
                        .param("endsAt", "2026-06-15T11:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createTimeOff_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(true);
        when(availabilityService.createTimeOff(any(), any(), any())).thenReturn(org.mockito.Mockito.mock(ProviderTimeOff.class));

        mockMvc.perform(post("/api/v1/providers/{providerId}/time-off", providerId)
                        .param("startsAt", "2026-07-01T00:00:00Z")
                        .param("endsAt", "2026-07-07T23:59:59Z"))
                .andExpect(status().isOk());
    }
}

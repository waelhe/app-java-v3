package com.marketplace.availability;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = AvailabilityController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class AvailabilityControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AvailabilityService availabilityService;

    @Test
    void getSlots_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(availabilityService.getSlots(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/providers/{providerId}/availability", providerId)
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-06-30T23:59:59Z"))
                .andExpect(status().isOk());
    }

    /**
     * CodeRabbit agent findings row 33 (CWE-200): the shared read path
     * serves ANY authenticated reader, so the wire contract must carry no
     * audit identities — {@code createdBy}/{@code updatedBy} held the
     * account subject, which for native registrations IS the login email
     * ({@code AuditorAwareImpl}). The proof is the serialized HTTP body
     * an unrelated reader actually receives: the operational fields
     * arrive, the identity fields are gone.
     */
    @Test
    void getSlots_carriesNoAuditIdentitiesOnTheWire() throws Exception {
        UUID providerId = UUID.randomUUID();
        AvailabilitySlotResponse slot = new AvailabilitySlotResponse(
                UUID.randomUUID(), providerId,
                Instant.parse("2026-06-15T10:00:00Z"), Instant.parse("2026-06-15T11:00:00Z"),
                false, 0L,
                Instant.parse("2026-06-01T09:00:00Z"), Instant.parse("2026-06-01T09:00:00Z"));
        when(availabilityService.getSlots(any(), any(), any())).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/v1/providers/{providerId}/availability", providerId)
                        .param("from", "2026-06-01T00:00:00Z")
                        .param("to", "2026-06-30T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].startsAt").exists())
                .andExpect(jsonPath("$[0].booked").exists())
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].createdBy").doesNotExist())
                .andExpect(jsonPath("$[0].updatedBy").doesNotExist());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createSlot_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(availabilityService.createSlot(any(), any(), any())).thenReturn(
                new AvailabilitySlotResponse(providerId, providerId, null, null, false, null, null, null));

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/slots", providerId)
                        .param("startsAt", "2026-06-15T10:00:00Z")
                        .param("endsAt", "2026-06-15T11:00:00Z"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createTimeOff_returnsOk() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(availabilityService.createTimeOff(any(), any(), any())).thenReturn(
                new ProviderTimeOffResponse(providerId, null, null, null));

        mockMvc.perform(post("/api/v1/providers/{providerId}/time-off", providerId)
                        .param("startsAt", "2026-07-01T00:00:00Z")
                        .param("endsAt", "2026-07-07T23:59:59Z"))
                .andExpect(status().isOk());
    }
}

package com.marketplace.availability;

import com.marketplace.shared.security.AuthHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
class AvailabilityAuthorizationIntegrationTest {

    @Autowired
    private WebApplicationContext wac;

    @MockitoBean
    private AuthHelper authHelper;

    private MockMvc mockMvc;

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders
                .webAppContextSetup(wac)
                .apply(springSecurity())
                .build();
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createSlot_whenNotOwner_returnsForbidden() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/slots", providerId)
                        .param("startsAt", "2026-06-15T10:00:00Z")
                        .param("endsAt", "2026-06-15T11:00:00Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createTimeOff_whenNotOwner_returnsForbidden() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/providers/{providerId}/time-off", providerId)
                        .param("startsAt", "2026-07-01T00:00:00Z")
                        .param("endsAt", "2026-07-07T23:59:59Z"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createRule_whenNotOwner_returnsForbidden() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(authHelper.ownsProvider(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/rules", providerId)
                        .param("dayOfWeek", "MONDAY")
                        .param("startTime", "09:00:00")
                        .param("endTime", "17:00:00"))
                .andExpect(status().isForbidden());
    }
}

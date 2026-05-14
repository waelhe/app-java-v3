package com.marketplace.availability;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.accept.ApiVersionStrategy;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AvailabilityControllerTest {

    private static ApiVersionStrategy apiVersionStrategy() {
        var configurer = new ApiVersionConfigurer() {
            ApiVersionStrategy build() {
                return getApiVersionStrategy();
            }
        };
        configurer.useRequestHeader("X-API-Version").setDefaultVersion("1.0");
        return configurer.build();
    }

    private final AvailabilityService service = mock(AvailabilityService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new AvailabilityController(service))
            .setApiVersionStrategy(apiVersionStrategy())
            .build();

    @Test
    void createSlot() throws Exception {
        UUID providerId = UUID.randomUUID();
        AvailabilitySlot slot = AvailabilitySlot.open(providerId, Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T11:00:00Z"));
        when(service.createSlot(any(), any(), any())).thenReturn(slot);

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/slots", providerId)
                        .param("startsAt", "2026-01-01T10:00:00Z")
                        .param("endsAt", "2026-01-01T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.providerId").value(providerId.toString()))
                .andExpect(jsonPath("$.booked").value(false));
    }

    @Test
    void getSlots() throws Exception {
        UUID providerId = UUID.randomUUID();
        when(service.getSlots(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/providers/{providerId}/availability", providerId)
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-01-02T00:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void createRule() throws Exception {
        UUID providerId = UUID.randomUUID();
        ProviderAvailabilityRule rule = ProviderAvailabilityRule.create(providerId, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(17, 0));
        when(service.createRule(any(), any(), any(), any())).thenReturn(rule);

        mockMvc.perform(post("/api/v1/providers/{providerId}/availability/rules", providerId)
                        .param("dayOfWeek", "MONDAY")
                        .param("startTime", "09:00:00")
                        .param("endTime", "17:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString());
    }

    @Test
    void createTimeOff() throws Exception {
        UUID providerId = UUID.randomUUID();
        ProviderTimeOff timeOff = ProviderTimeOff.create(providerId, Instant.parse("2026-01-01T10:00:00Z"), Instant.parse("2026-01-01T11:00:00Z"));
        when(service.createTimeOff(any(), any(), any())).thenReturn(timeOff);

        mockMvc.perform(post("/api/v1/providers/{providerId}/time-off", providerId)
                        .param("startsAt", "2026-01-01T10:00:00Z")
                        .param("endsAt", "2026-01-01T11:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isString());
    }
}

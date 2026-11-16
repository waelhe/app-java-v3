package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * L26 (feature-expansion roadmap §5): the host's price-calendar controller
 * slice — the role gate (PROVIDER/ADMIN only), the boundary validation
 * (malformed payloads are 400 before any service call), the surface
 * statuses (PUT upsert 200, POST 201, DELETE 204), and the
 * {@code Authentication} hand-off to the ownership-verifying service.
 * Listing ownership/409/404 semantics live in the integration test
 * (real Flyway schema + real service).
 */
@WebMvcTest(controllers = ListingPriceCalendarController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
@WithMockUser(roles = "PROVIDER")
class ListingPriceCalendarControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListingPriceCalendarService calendarService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    @WithMockUser(roles = "USER")
    void getCalendar_withUserRole_returnsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/listings/{listingId}/calendar", UUID.randomUUID()))
                .andExpect(status().isForbidden());
    }

    @Test
    void upsertWeekendRule_returnsOkWithTheRule() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(calendarService.upsertWeekendRule(any(), any(), any())).thenReturn(
                new WeekendRuleResponse(UUID.randomUUID(), listingId, new BigDecimal("1.2"), null, null));

        mockMvc.perform(put("/api/v1/pricing/listings/{listingId}/calendar/weekend-rule", listingId)
                        .contentType("application/json")
                        .content("""
                                {"multiplier": 1.2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.listingId").value(listingId.toString()))
                .andExpect(jsonPath("$.multiplier").value(1.2));
    }

    @Test
    void upsertWeekendRule_missingMultiplier_returns400BeforeAnyServiceCall() throws Exception {
        mockMvc.perform(put("/api/v1/pricing/listings/{listingId}/calendar/weekend-rule", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(calendarService);
    }

    @Test
    void upsertWeekendRule_outOfRangeMultiplier_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/pricing/listings/{listingId}/calendar/weekend-rule", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"multiplier": 0}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(calendarService);
    }

    @Test
    void deleteWeekendRule_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/pricing/listings/{listingId}/calendar/weekend-rule", UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }

    @Test
    void addSeasonalRate_returnsCreated() throws Exception {
        UUID listingId = UUID.randomUUID();
        when(calendarService.addSeasonalRate(any(), any(), any(), any(Long.class), any())).thenReturn(
                new SeasonalRateResponse(UUID.randomUUID(), listingId,
                        LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, null, null));

        mockMvc.perform(post("/api/v1/pricing/listings/{listingId}/calendar/seasonal-rates", listingId)
                        .contentType("application/json")
                        .content("""
                                {"fromDate": "2026-01-15", "toDate": "2026-01-16", "priceCents": 20000}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fromDate").value("2026-01-15"))
                .andExpect(jsonPath("$.priceCents").value(20000));
    }

    @Test
    void addSeasonalRate_missingDates_returns400BeforeAnyServiceCall() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/listings/{listingId}/calendar/seasonal-rates", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"priceCents": 20000}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(calendarService);
    }

    @Test
    void addSeasonalRate_negativePrice_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/pricing/listings/{listingId}/calendar/seasonal-rates", UUID.randomUUID())
                        .contentType("application/json")
                        .content("""
                                {"fromDate": "2026-01-15", "toDate": "2026-01-16", "priceCents": -5}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(calendarService);
    }

    @Test
    void updateSeasonalRate_returnsOk() throws Exception {
        UUID listingId = UUID.randomUUID();
        UUID rateId = UUID.randomUUID();
        when(calendarService.updateSeasonalRate(any(), any(), any(), any(), any(Long.class), any()))
                .thenReturn(new SeasonalRateResponse(rateId, listingId,
                        LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-05"), 30_000L, null, null));

        mockMvc.perform(put("/api/v1/pricing/listings/{listingId}/calendar/seasonal-rates/{rateId}",
                        listingId, rateId)
                        .contentType("application/json")
                        .content("""
                                {"fromDate": "2026-02-01", "toDate": "2026-02-05", "priceCents": 30000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rateId.toString()));
    }

    @Test
    void deleteSeasonalRate_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/api/v1/pricing/listings/{listingId}/calendar/seasonal-rates/{rateId}",
                        UUID.randomUUID(), UUID.randomUUID()))
                .andExpect(status().isNoContent());
    }
}

package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * L26 (feature-expansion roadmap §5): the calendar controller's contract —
 * the {@code PricingRuleControllerTest} pattern (direct invocation, no
 * slice): every endpoint maps to its service method with the request's
 * values and the caller's {@code Authentication}, and the surface
 * statuses are 200 (GET/PUT), 201 (POST), 204 (DELETE).
 */
class ListingPriceCalendarControllerTest {

    private final ListingPriceCalendarService calendarService = mock(ListingPriceCalendarService.class);
    private final ListingPriceCalendarController controller =
            new ListingPriceCalendarController(calendarService);
    private final Authentication authentication = mock(Authentication.class);

    @Test
    void getCalendar_returnsTheServiceBody() {
        UUID listingId = UUID.randomUUID();
        ListingCalendarResponse body = new ListingCalendarResponse(listingId, null, java.util.List.of());
        when(calendarService.getCalendar(listingId, authentication)).thenReturn(body);

        assertEquals(body, controller.getCalendar(listingId, authentication));
        verify(calendarService).getCalendar(listingId, authentication);
    }

    @Test
    void upsertWeekendRule_returnsOkWithTheRule() {
        UUID listingId = UUID.randomUUID();
        WeekendRuleResponse rule = new WeekendRuleResponse(UUID.randomUUID(), listingId,
                new BigDecimal("1.2"), null, null);
        when(calendarService.upsertWeekendRule(listingId, new BigDecimal("1.2"), authentication))
                .thenReturn(rule);

        var result = controller.upsertWeekendRule(listingId,
                new ListingPriceCalendarController.UpsertWeekendRuleRequest(new BigDecimal("1.2")),
                authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(rule, result.getBody());
    }

    @Test
    void deleteWeekendRule_returnsNoContent() {
        UUID listingId = UUID.randomUUID();

        var result = controller.deleteWeekendRule(listingId, authentication);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        assertNull(result.getBody());
        verify(calendarService).deleteWeekendRule(listingId, authentication);
    }

    @Test
    void addSeasonalRate_returnsCreated() {
        UUID listingId = UUID.randomUUID();
        SeasonalRateResponse rate = new SeasonalRateResponse(UUID.randomUUID(), listingId,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, null, null);
        when(calendarService.addSeasonalRate(listingId,
                LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L, authentication))
                .thenReturn(rate);

        var result = controller.addSeasonalRate(listingId,
                new ListingPriceCalendarController.SeasonalRateRequest(
                        LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L),
                authentication);

        assertEquals(HttpStatus.CREATED, result.getStatusCode());
        assertEquals(rate, result.getBody());
    }

    @Test
    void updateSeasonalRate_returnsOk() {
        UUID listingId = UUID.randomUUID();
        UUID rateId = UUID.randomUUID();
        SeasonalRateResponse rate = new SeasonalRateResponse(rateId, listingId,
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-05"), 30_000L, null, null);
        when(calendarService.updateSeasonalRate(listingId, rateId,
                LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-05"), 30_000L, authentication))
                .thenReturn(rate);

        var result = controller.updateSeasonalRate(listingId, rateId,
                new ListingPriceCalendarController.SeasonalRateRequest(
                        LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-05"), 30_000L),
                authentication);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(rate, result.getBody());
    }

    @Test
    void deleteSeasonalRate_returnsNoContent() {
        UUID listingId = UUID.randomUUID();
        UUID rateId = UUID.randomUUID();

        var result = controller.deleteSeasonalRate(listingId, rateId, authentication);

        assertEquals(HttpStatus.NO_CONTENT, result.getStatusCode());
        verify(calendarService).deleteSeasonalRate(listingId, rateId, authentication);
    }

    @Test
    void conflictPropagates_the409Taxonomy() {
        UUID listingId = UUID.randomUUID();
        when(calendarService.addSeasonalRate(any(), any(), any(), any(Long.class), any()))
                .thenThrow(new com.marketplace.shared.api.ConflictException("overlap"));

        assertThrows(com.marketplace.shared.api.ConflictException.class, () -> controller.addSeasonalRate(
                listingId,
                new ListingPriceCalendarController.SeasonalRateRequest(
                        LocalDate.parse("2026-01-15"), LocalDate.parse("2026-01-16"), 20_000L),
                authentication));
    }
}

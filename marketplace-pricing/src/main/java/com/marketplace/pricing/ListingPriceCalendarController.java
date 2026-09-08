package com.marketplace.pricing;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * L26 (feature-expansion roadmap §5, Week 3): the host's price-calendar
 * CRUD — {@code /api/v1/pricing/listings/{listingId}/calendar} with the
 * nested weekend-rule and seasonal-rates resources. Role gate at the class
 * level ({@code PROVIDER}/{@code ADMIN}); the LISTING ownership (403 for a
 * listing the caller does not own, 404 for an unknown one) is verified by
 * {@link ListingPriceCalendarService#requireOwnedListing} — resolution and
 * authorization are two independent checks (the L25 lesson).
 *
 * <p>Surface contract (roadmap "نقاط CRUD للمضيف"):
 * <ul>
 *   <li>GET the whole calendar — the weekend rule (null = the flat model)
 *       plus the seasonal ranges ordered by start date;</li>
 *   <li>PUT the weekend rule — an UPSERT (create or re-tune the single
 *       live row);</li>
 *   <li>DELETE the weekend rule — back to the flat model on weekends;</li>
 *   <li>POST / PUT / DELETE the seasonal ranges — a real overlap with a
 *       sibling is 409 (ConflictException taxonomy), adjacent ranges
 *       sharing a boundary are legal (open intervals).</li>
 * </ul>
 * Validation records reject malformed payloads at the boundary (the 400
 * taxonomy) before any service call.
 */
@RestController
@RequestMapping(value = ApiConstants.PRICING + "/listings/{listingId}/calendar", version = "1.0")
@PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
public class ListingPriceCalendarController {

    private final ListingPriceCalendarService calendarService;

    public ListingPriceCalendarController(ListingPriceCalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @GetMapping
    public ListingCalendarResponse getCalendar(@PathVariable UUID listingId,
                                               Authentication authentication) {
        return calendarService.getCalendar(listingId, authentication);
    }

    @PutMapping("/weekend-rule")
    public ResponseEntity<WeekendRuleResponse> upsertWeekendRule(
            @PathVariable UUID listingId,
            @Valid @RequestBody UpsertWeekendRuleRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(calendarService.upsertWeekendRule(
                listingId, request.multiplier(), authentication));
    }

    @DeleteMapping("/weekend-rule")
    public ResponseEntity<Void> deleteWeekendRule(@PathVariable UUID listingId,
                                                  Authentication authentication) {
        calendarService.deleteWeekendRule(listingId, authentication);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/seasonal-rates")
    public ResponseEntity<SeasonalRateResponse> addSeasonalRate(
            @PathVariable UUID listingId,
            @Valid @RequestBody SeasonalRateRequest request,
            Authentication authentication) {
        SeasonalRateResponse response = calendarService.addSeasonalRate(
                listingId, request.fromDate(), request.toDate(), request.priceCents(), authentication);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/seasonal-rates/{rateId}")
    public ResponseEntity<SeasonalRateResponse> updateSeasonalRate(
            @PathVariable UUID listingId,
            @PathVariable UUID rateId,
            @Valid @RequestBody SeasonalRateRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(calendarService.updateSeasonalRate(
                listingId, rateId, request.fromDate(), request.toDate(), request.priceCents(), authentication));
    }

    @DeleteMapping("/seasonal-rates/{rateId}")
    public ResponseEntity<Void> deleteSeasonalRate(@PathVariable UUID listingId,
                                                   @PathVariable UUID rateId,
                                                   Authentication authentication) {
        calendarService.deleteSeasonalRate(listingId, rateId, authentication);
        return ResponseEntity.noContent().build();
    }

    /**
     * The weekend multiplier on the base price — same bounds the entity
     * factory enforces ({@code (0, 10]}, the 3-digit scale of V41).
     */
    public record UpsertWeekendRuleRequest(
            @NotNull
            @DecimalMin(value = "0", inclusive = false, message = "Weekend multiplier must be > 0")
            @DecimalMax(value = "10", inclusive = true, message = "Weekend multiplier must be <= 10")
            BigDecimal multiplier
    ) {}

    /**
     * One seasonal range: {@code [fromDate, toDate)} with an EXCLUSIVE end
     * and a non-negative absolute price in minor units.
     */
    public record SeasonalRateRequest(
            @NotNull LocalDate fromDate,
            @NotNull LocalDate toDate,
            @DecimalMin(value = "0", message = "Seasonal price must not be negative")
            long priceCents
    ) {}
}

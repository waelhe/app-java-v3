package com.marketplace.availability;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class AvailabilityController {

    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) {
        this.availabilityService = availabilityService;
    }

    @PostMapping("/providers/{providerId}/availability/slots")
    @Operation(summary = "Create an availability slot",
            description = "Publishes one bookable slot window [startsAt, endsAt) for a provider.")
    public ResponseEntity<AvailabilitySlot> createSlot(
            @PathVariable UUID providerId,
            @Parameter(description = "Slot start (inclusive), ISO-8601 instant", example = "2026-10-01T14:00:00Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startsAt,
            @Parameter(description = "Slot end (exclusive), ISO-8601 instant", example = "2026-10-08T10:00:00Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endsAt) {
        return ResponseEntity.ok(availabilityService.createSlot(providerId, startsAt, endsAt));
    }

    @GetMapping("/providers/{providerId}/availability")
    @Operation(summary = "Read a provider's availability",
            description = "The provider's slots in the requested window — the search-side "
                    + "availability source (L27 consumes the same data via a port).")
    public ResponseEntity<List<AvailabilitySlot>> getSlots(
            @PathVariable UUID providerId,
            @Parameter(description = "Window start, ISO-8601 instant", example = "2026-10-01T00:00:00Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Window end, ISO-8601 instant", example = "2026-10-31T23:59:59Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(availabilityService.getSlots(providerId, from, to));
    }

    @PostMapping("/providers/{providerId}/availability/rules")
    @Operation(summary = "Create a weekly availability rule",
            description = "A recurring weekly window the slot generator expands into concrete slots.")
    public ResponseEntity<ProviderAvailabilityRule> createRule(
            @PathVariable UUID providerId,
            @Parameter(description = "Day of the week the rule applies to", example = "FRIDAY")
            @RequestParam DayOfWeek dayOfWeek,
            @Parameter(description = "Rule start time of day", example = "09:00")
            @RequestParam LocalTime startTime,
            @Parameter(description = "Rule end time of day", example = "18:00")
            @RequestParam LocalTime endTime) {
        return ResponseEntity.ok(availabilityService.createRule(providerId, dayOfWeek, startTime, endTime));
    }

    @PostMapping("/providers/{providerId}/time-off")
    @Operation(summary = "Block time off",
            description = "Marks a window unavailable — conflicts with booking and search "
                    + "availability.")
    public ResponseEntity<ProviderTimeOff> createTimeOff(
            @PathVariable UUID providerId,
            @Parameter(description = "Time-off start (inclusive), ISO-8601 instant", example = "2026-10-12T00:00:00Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startsAt,
            @Parameter(description = "Time-off end (exclusive), ISO-8601 instant", example = "2026-10-19T00:00:00Z")
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endsAt) {
        return ResponseEntity.ok(availabilityService.createTimeOff(providerId, startsAt, endsAt));
    }
}

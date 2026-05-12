package com.marketplace.availability;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.AVAILABILITY, version = "1.0")
public class AvailabilityController {
    private final AvailabilityService availabilityService;

    public AvailabilityController(AvailabilityService availabilityService) { this.availabilityService = availabilityService; }

    @PostMapping("/providers/{providerId}/rules")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ResponseEntity<ProviderAvailabilityRule> addRule(@PathVariable UUID providerId, @Valid @RequestBody AvailabilityRuleRequest request, Authentication authentication) {
        ProviderAvailabilityRule rule = availabilityService.addRule(providerId, request.dayOfWeek(), request.startsAt(), request.endsAt(), request.slotMinutes(), authentication);
        return ResponseEntity.created(URI.create(ApiConstants.AVAILABILITY + "/providers/" + providerId + "/rules/" + rule.getId())).body(rule);
    }

    @GetMapping("/providers/{providerId}/rules")
    public ResponseEntity<List<ProviderAvailabilityRule>> listRules(@PathVariable UUID providerId) {
        return ResponseEntity.ok(availabilityService.listRules(providerId));
    }

    @PostMapping("/providers/{providerId}/time-off")
    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ResponseEntity<ProviderTimeOff> addTimeOff(@PathVariable UUID providerId, @Valid @RequestBody TimeOffRequest request, Authentication authentication) {
        return ResponseEntity.ok(availabilityService.addTimeOff(providerId, request.startsAt(), request.endsAt(), request.reason(), authentication));
    }

    @GetMapping("/providers/{providerId}/time-off")
    public ResponseEntity<List<ProviderTimeOff>> listTimeOff(@PathVariable UUID providerId) {
        return ResponseEntity.ok(availabilityService.listTimeOff(providerId));
    }

    @GetMapping("/providers/{providerId}/listings/{listingId}/slots")
    public ResponseEntity<List<AvailabilitySlot>> slots(@PathVariable UUID providerId, @PathVariable UUID listingId,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(availabilityService.slots(providerId, listingId, date));
    }

    public record AvailabilityRuleRequest(@NotNull @Min(1) @Max(7) Integer dayOfWeek, @NotNull LocalTime startsAt,
                                          @NotNull LocalTime endsAt, @NotNull @Min(5) Integer slotMinutes) {}
    public record TimeOffRequest(@NotNull Instant startsAt, @NotNull Instant endsAt, String reason) {}
}

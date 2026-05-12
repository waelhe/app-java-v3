package com.marketplace.availability;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
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
    public ResponseEntity<AvailabilitySlot> createSlot(@PathVariable UUID providerId,
                                                       @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startsAt,
                                                       @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endsAt) {
        return ResponseEntity.ok(availabilityService.createSlot(providerId, startsAt, endsAt));
    }

    @GetMapping("/providers/{providerId}/availability")
    public ResponseEntity<List<AvailabilitySlot>> getSlots(@PathVariable UUID providerId,
                                                           @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                                           @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(availabilityService.getSlots(providerId, from, to));
    }
}

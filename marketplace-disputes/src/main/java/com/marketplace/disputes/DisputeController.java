package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class DisputeController {

    private final DisputeService service;

    public DisputeController(DisputeService service) {
        this.service = service;
    }

    @PostMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<Dispute> open(@PathVariable UUID bookingId, @RequestParam @NotBlank String reason, Authentication authentication) {
        return ResponseEntity.ok(service.open(bookingId, reason, authentication));
    }

    @GetMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<List<Dispute>> list(@PathVariable UUID bookingId, Authentication authentication) {
        return ResponseEntity.ok(service.listForBooking(bookingId, authentication));
    }

    @PostMapping("/admin/disputes/{id}/resolve")
    public ResponseEntity<Dispute> resolve(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(service.resolve(id, authentication));
    }
}

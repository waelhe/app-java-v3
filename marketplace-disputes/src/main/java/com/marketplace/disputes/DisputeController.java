package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * REST Controller for dispute management.
 *
 * <p>Class-level {@code @PreAuthorize("isAuthenticated()")} ensures all endpoints
 * require authentication. Method-level annotations override for admin-only endpoints.
 *
 * <p>Reference: Spring Security Reference -- Method Security:
 * "If it is at the class level... then all methods inherit the class-level behavior.
 * Methods declaring the annotation override the class-level annotation."
 * https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Validated
@PreAuthorize("isAuthenticated()")
public class DisputeController {

    private final DisputeService service;
    private final DisputeMapper disputeMapper;

    public DisputeController(DisputeService service, DisputeMapper disputeMapper) {
        this.service = service;
        this.disputeMapper = disputeMapper;
    }

    @PostMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<DisputeResponse> open(@PathVariable UUID bookingId, @RequestParam @NotBlank String reason, Authentication authentication) {
        return ResponseEntity.ok(disputeMapper.toResponse(service.open(bookingId, reason, authentication)));
    }

    @GetMapping("/bookings/{bookingId}/disputes")
    public ResponseEntity<List<DisputeResponse>> list(@PathVariable UUID bookingId, Authentication authentication) {
        List<DisputeResponse> disputes = service.listForBooking(bookingId, authentication).stream()
                .map(disputeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(disputes);
    }

    @PostMapping("/admin/disputes/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<DisputeResponse> resolve(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(disputeMapper.toResponse(service.resolve(id, authentication)));
    }
}

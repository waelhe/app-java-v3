package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Validated
public class DisputeController {

    private final DisputeService service;
    private final DisputeMapper disputeMapper;

    public DisputeController(DisputeService service, DisputeMapper disputeMapper) {
        this.service = service;
        this.disputeMapper = disputeMapper;
    }

    @PostMapping("/bookings/{bookingId}/disputes")
    @Operation(summary = "Open a dispute on a booking",
            description = "Opens a dispute on the caller's own booking with a mandatory "
                    + "reason — the consumer-side entry into the dispute flow (OPEN state).")
    public ResponseEntity<DisputeResponse> open(@PathVariable UUID bookingId, @RequestParam @NotBlank String reason, Authentication authentication) {
        return ResponseEntity.ok(disputeMapper.toResponse(service.open(bookingId, reason, authentication)));
    }

    @GetMapping("/bookings/{bookingId}/disputes")
    @Operation(summary = "List a booking's disputes",
            description = "The dispute trail of one booking — visibility-scoped to the "
                    + "booking's participants (consumer/provider) and administrators.")
    public ResponseEntity<List<DisputeResponse>> list(@PathVariable UUID bookingId, Authentication authentication) {
        List<DisputeResponse> disputes = service.listForBooking(bookingId, authentication).stream()
                .map(disputeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(disputes);
    }

    /**
     * L24: the resolve decision carries the financial outcome — the body's
     * {@code resolution} selects REFUND_CONSUMER / RELEASE_PROVIDER /
     * NO_ACTION (roadmap §5). The body is OPTIONAL for backward
     * compatibility: a body-less call resolves with NO_ACTION — exactly the
     * endpoint's pre-L24 semantics (resolve, no money movement) — so the
     * public contract stays compatible (the OpenAPI gate) and money never
     * moves implicitly: REFUND_CONSUMER must be named explicitly.
     */
    @PostMapping("/admin/disputes/{id}/resolve")
    @Operation(summary = "Resolve a dispute (administrative)",
            description = "L24: the resolution selects the financial outcome — "
                    + "REFUND_CONSUMER / RELEASE_PROVIDER / NO_ACTION. The body is OPTIONAL "
                    + "for backward compatibility: a body-less call resolves with NO_ACTION "
                    + "(resolve, no money movement); REFUND_CONSUMER must be named explicitly "
                    + "— money never moves implicitly.")
    public ResponseEntity<DisputeResponse> resolve(@PathVariable UUID id,
                                                    @Valid @RequestBody(required = false) ResolveDisputeRequest request,
                                                    Authentication authentication) {
        DisputeResolution resolution = request == null ? DisputeResolution.NO_ACTION : request.resolution();
        return ResponseEntity.ok(disputeMapper.toResponse(service.resolve(id, resolution, authentication)));
    }
}

package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.DISPUTES, version = "1.0")
public class DisputeController {
    private final DisputeService disputeService;
    public DisputeController(DisputeService disputeService) { this.disputeService = disputeService; }

    @PostMapping
    public ResponseEntity<Dispute> create(@Valid @RequestBody CreateDisputeRequest request, Authentication authentication) {
        return ResponseEntity.ok(disputeService.create(request.bookingId(), request.reason(), authentication));
    }

    @GetMapping
    public ResponseEntity<PagedResponse<Dispute>> list(Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(disputeService.listMine(pageable, authentication)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Dispute> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(disputeService.getForUser(id, authentication));
    }

    @PostMapping("/{id}/messages")
    public ResponseEntity<DisputeMessage> message(@PathVariable UUID id, @Valid @RequestBody AddMessageRequest request, Authentication authentication) {
        return ResponseEntity.ok(disputeService.addMessage(id, request.message(), authentication));
    }


    public record CreateDisputeRequest(@NotNull UUID bookingId, @NotBlank String reason) {}
    public record AddMessageRequest(@NotBlank String message) {}
}

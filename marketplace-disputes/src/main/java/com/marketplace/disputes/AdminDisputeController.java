package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.ADMIN + "/disputes", version = "1.0")
public class AdminDisputeController {
    private final DisputeService disputeService;

    public AdminDisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Dispute> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveDisputeRequest request) {
        return ResponseEntity.ok(disputeService.resolve(id, request.resolution(), request.refundRecommended()));
    }

    public record ResolveDisputeRequest(@NotBlank String resolution, boolean refundRecommended) {
    }
}

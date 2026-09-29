package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Administrative, manually-operated first verifier. It deliberately has no provider dependency. */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class NeighborhoodVerificationAdminController {
    private final NeighborhoodMembershipService membershipService;

    public NeighborhoodVerificationAdminController(NeighborhoodMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @PostMapping("/neighborhood-memberships/{membershipId}/verification")
    @Operation(summary = "Approve or reject a pending neighborhood verification")
    public ResponseEntity<NeighborhoodMembershipView> review(@PathVariable UUID membershipId,
                                                               @RequestParam String decision) {
        boolean approve = switch (decision.trim()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new BadRequestException("Invalid decision '" + decision
                    + "' — valid values: APPROVE, REJECT");
        };
        return ResponseEntity.ok(membershipService.reviewVerification(membershipId, approve));
    }
}

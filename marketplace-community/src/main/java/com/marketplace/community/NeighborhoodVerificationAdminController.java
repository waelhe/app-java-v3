package com.marketplace.community;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Administrative, manually-operated first verifier. It deliberately has no
 * provider dependency. Two surfaces: the reviewable queue (the lifecycle's
 * own read — the moderation queue's shape: the optional state axis, the
 * complete drain order, deterministic pagination) and the one transition
 * (APPROVE/REJECT — the only way out of PENDING).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class NeighborhoodVerificationAdminController {
    private final NeighborhoodMembershipService membershipService;

    public NeighborhoodVerificationAdminController(NeighborhoodMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/neighborhood-memberships")
    @Operation(summary = "Read the verification queue (admin)",
            description = "The membership ledger by verification state — the optional state axis "
                    + "filters UNVERIFIED/PENDING/VERIFIED/REJECTED; absent = the whole ACTIVE "
                    + "ledger (left memberships are hidden by the read's own filter). PENDING is "
                    + "the reviewable queue on its complete drain order (the state's own clock: "
                    + "updatedAt ASC, id ASC — oldest pending claim first). Deterministic "
                    + "pagination; read-only.")
    public ResponseEntity<PagedResponse<NeighborhoodMembershipView>> queue(
            @Parameter(description = "Optional verification state filter — UNVERIFIED, PENDING, "
                    + "VERIFIED or REJECTED")
            @RequestParam(required = false) String state,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                membershipService.getVerificationQueue(parseState(state), pageable)));
    }

    @PostMapping("/neighborhood-memberships/{membershipId}/verification")
    @Operation(summary = "Approve or reject a pending neighborhood verification",
            description = "The ONE transition out of PENDING. APPROVE moves the membership to "
                    + "VERIFIED («جار موثق» — the trust mark); REJECT moves it to REJECTED "
                    + "(community writes and new direct chats are denied; reads stay open — "
                    + "D-N3's own split). A non-PENDING membership answers 409 with the "
                    + "entity's own transition words.")
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

    /** The state axis's own parse — 400 before any read for anything else. */
    private MembershipVerificationState parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return MembershipVerificationState.valueOf(raw.trim());
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("Invalid state '" + raw
                    + "' — valid values: UNVERIFIED, PENDING, VERIFIED, REJECTED");
        }
    }
}

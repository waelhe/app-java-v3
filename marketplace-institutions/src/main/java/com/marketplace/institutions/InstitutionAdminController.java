package com.marketplace.institutions;

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
 * B-13 (compliance plan C.3 — التحقق المؤسسي): the administrative
 * review surface, on the {@code NeighborhoodVerificationAdminController}
 * house shape — the class-level {@code @PreAuthorize("hasRole('ADMIN')")}
 * gate (the Security method-security reference's own channel), the
 * reviewable queue (the optional state axis, the complete drain order),
 * and the verdict's ONLY mover (APPROVE/REJECT).
 */
@RestController
@RequestMapping(value = ApiConstants.ADMIN, version = "1.0")
@PreAuthorize("hasRole('ADMIN')")
public class InstitutionAdminController {

    private final InstitutionService institutionService;

    public InstitutionAdminController(InstitutionService institutionService) {
        this.institutionService = institutionService;
    }

    @GetMapping("/institutions")
    @Operation(summary = "Read the institutional verification queue (admin)",
            description = "The registry ledger by verification state — the optional state axis "
                    + "filters UNVERIFIED/PENDING/VERIFIED/REJECTED; absent = the whole registry. "
                    + "PENDING is the reviewable queue on its complete drain order (the state's "
                    + "own clock: updatedAt ASC, id ASC — oldest pending claim first). "
                    + "Deterministic pagination; read-only.")
    public ResponseEntity<PagedResponse<InstitutionResponse>> queue(
            @Parameter(description = "Optional verification state filter — UNVERIFIED, PENDING, "
                    + "VERIFIED or REJECTED")
            @RequestParam(required = false) InstitutionVerificationState state,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                institutionService.reviewQueue(state, pageable)
                        .map(InstitutionResponse::from)));
    }

    @PostMapping("/institutions/{institutionId}/verification")
    @Operation(summary = "Approve or reject an institutional verification",
            description = "The verdict's ONLY mover, in both directions. APPROVE moves a PENDING "
                    + "institution to VERIFIED (the trust mark rides every public read from that "
                    + "moment) and RE-ADMITS a REJECTED one (the recovery lever); REJECT refuses a "
                    + "PENDING claim (the row stays — the honest registry — the mark never lands). "
                    + "Any other source or target state answers 409 with the entity's own "
                    + "transition words.")
    public ResponseEntity<InstitutionResponse> review(
            @PathVariable UUID institutionId, @RequestParam String decision) {
        boolean approve = switch (decision.trim()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new BadRequestException("Invalid decision '" + decision
                    + "' — expected APPROVE or REJECT");
        };
        return ResponseEntity.ok(InstitutionResponse.from(
                institutionService.review(institutionId, approve)));
    }
}

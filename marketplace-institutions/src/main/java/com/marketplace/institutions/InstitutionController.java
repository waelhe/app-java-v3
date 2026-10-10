package com.marketplace.institutions;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * B-13 (compliance plan C.3 — سجل الجهة): the registry's public and
 * representative surface, on the {@code LeadsController}/
 * {@code NeighborhoodMembershipController} house shapes — the public
 * board and detail (the JSON-LD block rides the detail alone, the
 * provider page's own pattern), the representative's own registry and
 * verification request.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class InstitutionController {

    private final InstitutionService institutionService;

    public InstitutionController(InstitutionService institutionService) {
        this.institutionService = institutionService;
    }

    @PostMapping("/institutions")
    @Operation(summary = "Register an institution",
            description = "The calling representative registers an institution — born UNVERIFIED on "
                    + "the public registry (the honest registry: visible with its state; the trust "
                    + "mark arrives only through the administrative review). The locationId gate "
                    + "answers 404 for an unknown geo node and 400 for a non-neighborhood node "
                    + "before any write.")
    public ResponseEntity<InstitutionResponse> register(
            @Valid @RequestBody InstitutionRequest request, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InstitutionResponse.from(institutionService.register(request, authentication)));
    }

    @GetMapping("/institutions")
    @Operation(summary = "The public institution registry board",
            description = "Paginated — every verification state (the state rides the response, the "
                    + "trust signal visible), the type and state axes optional filters.")
    public ResponseEntity<PagedResponse<InstitutionResponse>> board(
            @Parameter(description = "SCHOOL, UNIVERSITY, CLINIC, MOSQUE, CHARITY, GOVERNMENT, COMPANY or NGO.")
            @RequestParam(required = false) InstitutionType type,
            @Parameter(description = "UNVERIFIED, PENDING, VERIFIED or REJECTED — the verified-only browse axis.")
            @RequestParam(required = false) InstitutionVerificationState state,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                institutionService.searchBoard(type, state, pageable)
                        .map(InstitutionResponse::from)));
    }

    @GetMapping("/institutions/{id}")
    @Operation(summary = "Read one registry entry (the public page)",
            description = "The detail read — the row's own facts plus the schema.org Organization "
                    + "JSON-LD block assembled over the resolved administrative chain (the "
                    + "structured-data surface the public page serves). Unknown is 404.")
    public ResponseEntity<InstitutionResponse> detail(@PathVariable UUID id) {
        return ResponseEntity.ok(institutionService.getInstitutionDetail(id));
    }

    @GetMapping("/institutions/me")
    @Operation(summary = "List my registered institutions",
            description = "The calling representative's own registry entries — every state, newest "
                    + "first.")
    public ResponseEntity<PagedResponse<InstitutionResponse>> myInstitutions(
            Pageable pageable, Authentication authentication) {
        return ResponseEntity.ok(PagedResponse.of(
                institutionService.myInstitutions(authentication, pageable)
                        .map(InstitutionResponse::from)));
    }

    @PostMapping("/institutions/{id}/verification-request")
    @Operation(summary = "Request the administrative review",
            description = "The representative's request: UNVERIFIED → PENDING only — the review "
                    + "queue's own gate. A REJECTED entry cannot self-reverse (the recovery lever "
                    + "is the administrator's APPROVE); a foreign entry is a 404.")
    public ResponseEntity<InstitutionResponse> requestVerification(
            @PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(InstitutionResponse.from(
                institutionService.requestVerification(id, authentication)));
    }
}

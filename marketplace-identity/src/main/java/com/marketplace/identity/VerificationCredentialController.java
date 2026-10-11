package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The verification credential surfaces (ADR-0001, plan §Phase 1): the
 * self-service pair (submit + my history) and the admin queue (list +
 * decision + revocation). The ProviderFollowController shape — one
 * controller mapped at {@code API_V1} with the full paths per method —
 * because the family spans the self and the /admin namespaces.
 *
 * <p>Authorization: the self pair rides the authenticated chain (the
 * account always acts on ITS OWN credentials — the subject comes from the
 * token, never from a request parameter); the admin trio carries the
 * method-level third layer ({@code @PreAuthorize hasRole('ADMIN')}) on top
 * of the {@code /api/v1/admin/**} chain rule — the documented three-layer
 * pattern.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class VerificationCredentialController {

    private final VerificationCredentialService service;

    public VerificationCredentialController(VerificationCredentialService service) {
        this.service = service;
    }

    /** The vocabulary pinned at the request boundary (the ChangeStatusRequest pattern). */
    public record SubmitCredentialRequest(
            @NotBlank @Pattern(regexp = "IDENTITY|RESIDENCE|BUSINESS_OWNERSHIP|PROFESSIONAL_QUALIFICATION|OFFICIAL_PUBLISHER",
                    message = "credentialType must be one of IDENTITY, RESIDENCE, BUSINESS_OWNERSHIP, "
                            + "PROFESSIONAL_QUALIFICATION, OFFICIAL_PUBLISHER")
            String credentialType,
            @Size(max = 1024) String evidenceUri,
            @Size(max = 2000) String notes) {}

    public record DecisionRequest(
            @NotBlank @Pattern(regexp = "APPROVED|REJECTED",
                    message = "decision must be APPROVED or REJECTED")
            String decision,
            @Size(max = 2000) String notes) {}

    public record RevocationRequest(@Size(max = 2000) String notes) {}

    @PostMapping(ApiConstants.VERIFICATION_CREDENTIALS)
    @Operation(summary = "Submit a verification credential (self-service)",
            description = "The authenticated account submits evidence about ITSELF for one credential "
                    + "type. One ACTIVE (PENDING or APPROVED) application per type — a second live "
                    + "submission answers 409; after a terminal decision a new submission opens.")
    public ResponseEntity<VerificationCredentialResponse> submit(
            @Valid @RequestBody SubmitCredentialRequest request,
            Authentication authentication) {
        VerificationCredential credential = service.submit(
                requireSubject(authentication), request.credentialType(),
                request.evidenceUri(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(VerificationCredentialResponse.from(credential));
    }

    @GetMapping(ApiConstants.VERIFICATION_CREDENTIALS + "/mine")
    @Operation(summary = "List my verification credentials",
            description = "The authenticated account's own credential history, newest first.")
    public ResponseEntity<List<VerificationCredentialResponse>> mine(Authentication authentication) {
        return ResponseEntity.ok(service.mine(requireSubject(authentication)).stream()
                .map(VerificationCredentialResponse::from)
                .toList());
    }

    @GetMapping(ApiConstants.ADMIN + ApiConstants.VERIFICATION_CREDENTIALS_SUFFIX)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "The verification credential review queue (admin)",
            description = "Every credential, or one status filter — the admin review surface.")
    public ResponseEntity<Page<VerificationCredentialResponse>> adminPage(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        VerificationCredentialStatus parsed =
                status == null ? null : VerificationCredentialService.parseStatus(status);
        return ResponseEntity.ok(service.adminPage(parsed, pageable)
                .map(VerificationCredentialResponse::from));
    }

    @PostMapping(ApiConstants.ADMIN + ApiConstants.VERIFICATION_CREDENTIALS_SUFFIX
            + "/{id}/decision")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Decide a credential (admin)",
            description = "APPROVED or REJECTED from PENDING — the state machine answers 409 on every "
                    + "other transition. Deciding is EVIDENCE, never authority: no role is granted or "
                    + "removed by this call (ADR-0001); the role grant stays a separate administrative act.")
    public ResponseEntity<VerificationCredentialResponse> decide(
            @PathVariable UUID id,
            @Valid @RequestBody DecisionRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(VerificationCredentialResponse.from(service.decide(
                id, request.decision(), request.notes(), requireSubject(authentication))));
    }

    @PostMapping(ApiConstants.ADMIN + ApiConstants.VERIFICATION_CREDENTIALS_SUFFIX
            + "/{id}/revocation")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Revoke a standing credential (admin)",
            description = "APPROVED → REVOKED — the standing verification's withdrawal.")
    public ResponseEntity<VerificationCredentialResponse> revoke(
            @PathVariable UUID id,
            @Valid @RequestBody RevocationRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(VerificationCredentialResponse.from(service.revoke(
                id, request.notes(), requireSubject(authentication))));
    }

    /** The house convention: the plain Authentication narrowed by an instanceof guard. */
    private static String requireSubject(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken token) {
            return token.getToken().getSubject();
        }
        throw new BadRequestException("Unsupported authentication type: " + authentication);
    }
}

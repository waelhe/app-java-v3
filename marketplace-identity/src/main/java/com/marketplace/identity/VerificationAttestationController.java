package com.marketplace.identity;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.TrustType;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Phase 1 (the unified plan §10) — the general read outlet of the trust
 * vocabulary plus its self-service request leg: the caller reads its
 * OWN attestation history and requests verification for one §6.5 trust
 * type with its evidence. The review decisions (grant/reject) and the
 * revoke stay at the service boundary (the ADMIN gate, the A-07
 * three-layer pattern) — no admin HTTP surface in this wave; the
 * resource-server chain's {@code anyRequest().authenticated()} covers
 * both endpoints (no security-config change — the {@code FollowController}
 * house shape verbatim).
 *
 * <p>The subject is ALWAYS the caller (the /me-family rule: identity
 * from the authentication itself, never from a blind body) — a request
 * body carries only the trust type and the evidence reference. The
 * entity never crosses the wire (the
 * controllersMustNotDependOnJpaEntities gate): the responses speak
 * {@link VerificationAttestationView} only. An out-of-vocabulary
 * {@code trustType} fails the enum binding at the boundary (the honest
 * 400 — the closed §6.5 set is spelled in the schema below).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class VerificationAttestationController {

    private final VerificationAttestationService attestationService;
    private final CurrentUserProvider currentUserProvider;

    public VerificationAttestationController(VerificationAttestationService attestationService,
                                             CurrentUserProvider currentUserProvider) {
        this.attestationService = attestationService;
        this.currentUserProvider = currentUserProvider;
    }

    /** The request body — the vocabulary is pinned at the boundary, the evidence is required. */
    public record AttestationRequest(
            @NotNull
            @Schema(description = "The §6.5 trust type requested — the closed vocabulary "
                    + "VERIFIED_LOCAL_MEMBER | VERIFIED_BUSINESS | COMMUNITY_ENDORSEMENT | "
                    + "VERIFIED_SOURCE", example = "VERIFIED_LOCAL_MEMBER")
            TrustType trustType,
            @NotBlank
            @Size(max = 500)
            @Schema(description = "The evidence reference — a pointer at the verifying "
                    + "workflow's own record (never a copy of it)", maxLength = 500)
            String evidenceRef) {
    }

    /**
     * Record the caller's own verification request for one §6.5 trust
     * type — 201 on the fresh PENDING row; a standing GRANTED pair
     * answers the honest 409 (the fact is already granted) and the
     * review decision stays administrative.
     */
    @PostMapping("/me/trust-attestations")
    @Operation(summary = "Request a trust attestation for my account",
            description = "Records the caller's own verification request for one of §6.5's four "
                    + "trust types (VERIFIED_LOCAL_MEMBER — neighborhood/area affiliation evidence; "
                    + "VERIFIED_BUSINESS — business/provider verification; COMMUNITY_ENDORSEMENT — "
                    + "a community endorsement attributed to a real person; VERIFIED_SOURCE — "
                    + "official/verifiable-source content), with the evidence reference. The four "
                    + "types are separate facts — never one flag. A standing GRANTED attestation of "
                    + "the same type answers 409; the review decision stays administrative.")
    public ResponseEntity<VerificationAttestationView> request(
            @Valid @RequestBody AttestationRequest request,
            Authentication authentication) {
        UUID subjectUserId = currentUserProvider.getCurrentUserId(authentication);
        VerificationAttestationView view = attestationService.request(
                subjectUserId, request.trustType(), request.evidenceRef(),
                authentication != null ? authentication.getName() : null);
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    /**
     * The caller's own attestation history, newest first — every state
     * included (the pair's own record: pending, granted, rejected,
     * revoked).
     */
    @GetMapping("/me/trust-attestations")
    @Operation(summary = "List my trust attestations",
            description = "The caller's own verification attestations across all four §6.5 trust "
                    + "types, newest first, every state included (PENDING / GRANTED / REVOKED / "
                    + "REJECTED). The granted set is what the trust/provenance dimension later "
                    + "reads; supervision privileges are never gained or lost through a badge or "
                    + "an endorsement.")
    public ResponseEntity<List<VerificationAttestationView>> myAttestations(
            Authentication authentication) {
        UUID subjectUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(attestationService.listOwn(subjectUserId));
    }
}

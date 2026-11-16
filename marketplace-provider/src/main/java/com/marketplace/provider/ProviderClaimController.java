package com.marketplace.provider;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * J14 (the journeys register — «مطالبة صاحب العمل بالملف»): the business
 * claim cycle's HTTP surface, on the {@link ProviderController} house
 * shape — {@code @RequestMapping(ApiConstants.API_V1, version = "1.0")},
 * the ADMIN surfaces under {@code /admin/**} (the SecurityConfig chain
 * rule + the service's own {@code @PreAuthorize} gates, the
 * {@code ProviderController#verify} precedent), the member surfaces
 * authenticated with the {@link CurrentUserProvider} seam resolving the
 * caller ({@code NeighborhoodPostController}'s pattern — the app context
 * wires the real provider; tests {@code @MockitoBean} it).
 *
 * <p>The journey this surface serves (the register's J14 row): the
 * platform pre-seeds an unclaimed page (POST /admin/providers) → a member
 * claims it (POST /providers/{id}/claim — one live claim per page) → the
 * administrator reviews (POST /admin/provider-claims/{claimId}/review —
 * APPROVE hands the ownership over, REJECT frees the page for a fresh
 * claim) → the claimant may withdraw their own PENDING claim (DELETE
 * /providers/me/claims/{claimId}) — and the board reads the queue (GET
 * /admin/provider-claims).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class ProviderClaimController {

    private final ProviderClaimService claimService;
    private final ProviderMapper providerMapper;
    private final CurrentUserProvider currentUserProvider;

    public ProviderClaimController(ProviderClaimService claimService,
                                   ProviderMapper providerMapper,
                                   CurrentUserProvider currentUserProvider) {
        this.claimService = claimService;
        this.providerMapper = providerMapper;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * The claim submission's request shape: the claimant's declared
     * reach-back channel (required — how the reviewer contacts them) and
     * the claim's own words (optional).
     */
    public record ClaimRequest(
            @Schema(description = "How the reviewer reaches the claimant back — a phone, an email, "
                    + "an in-app handle", example = "+963-9xx-xxx-xxx")
            @NotBlank @Size(max = 200) String contactChannel,
            @Schema(description = "The claim's own words — why this page is theirs (optional)",
                    example = "أنا مالك المخبز منذ 2019 — الرخصة التجارية رقم 1149")
            @Size(max = 2000) String note
    ) {
    }

    /** The review's request shape: the verdict's own words (optional). */
    public record ReviewRequest(
            @Schema(description = "The verdict's own words (optional) — why the claim was "
                    + "approved or rejected", example = "الرخصة والهوية مطابقتان")
            @Size(max = 2000) String note
    ) {
    }

    @PostMapping("/admin/providers")
    @Operation(summary = "Pre-seed an unclaimed business page (administrative)",
            description = "J14: the platform's own pre-seed — the CLAIMABLE business page "
                    + "(the Yelp unclaimed-listing shape). user_id is NULL by construction: "
                    + "no member owns the page until a claim is APPROVED. Status starts "
                    + "PENDING (the platform's verification gate, like every profile's day "
                    + "one) and the ownership badge starts UNVERIFIED. The L36 persona "
                    + "fields ride the pre-seed like any creation.")
    public ResponseEntity<ProviderResponse> createUnclaimed(@Valid @RequestBody ProviderRequest request,
                                                            Authentication authentication) {
        return ResponseEntity.ok(providerMapper.toResponse(
                claimService.createUnclaimedProfile(request, actor(authentication))));
    }

    @PostMapping("/providers/{id}/claim")
    @Operation(summary = "Claim an unclaimed business page",
            description = "J14: submits the caller's ownership claim on a pre-seeded, "
                    + "unclaimed page — born PENDING, exactly ONE live claim per page "
                    + "(a second claim while one is under review answers 409; a claim on "
                    + "an already-owned page answers 409; an unknown page answers 404). "
                    + "The administrative review resolves it: APPROVE hands the page's "
                    + "ownership to the claimant.")
    public ResponseEntity<ProviderClaimResponse> submitClaim(@PathVariable UUID id,
                                                             @Valid @RequestBody ClaimRequest request,
                                                             Authentication authentication) {
        UUID claimantUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(ProviderClaimResponse.of(claimService.submitClaim(
                        id, claimantUserId, request.contactChannel(), request.note())));
    }

    @PostMapping("/admin/provider-claims/{claimId}/review")
    @Operation(summary = "Review a business claim (administrative)",
            description = "J14: the verdict's only mover. decision=APPROVE hands the page's "
                    + "ownership to the claimant (provider_profiles.user_id = claimant, "
                    + "claimed_at set — the ownership handover, one transaction with the "
                    + "claim's own APPROVED flip); decision=REJECT leaves the page unowned "
                    + "and claimable again (the claimant's recovery is a FRESH claim). "
                    + "Only a PENDING claim can be reviewed — any decided state answers 409.")
    public ResponseEntity<ProviderClaimResponse> reviewClaim(@PathVariable UUID claimId,
                                                             @Parameter(description = "The verdict — "
                                                                     + "APPROVE or REJECT")
                                                             @RequestParam("decision") String decision,
                                                             @Valid @RequestBody ReviewRequest request,
                                                             Authentication authentication) {
        boolean approve;
        if ("APPROVE".equalsIgnoreCase(decision.trim())) {
            approve = true;
        } else if ("REJECT".equalsIgnoreCase(decision.trim())) {
            approve = false;
        } else {
            throw new BadRequestException(
                    "decision must be APPROVE or REJECT — got '" + decision + "'");
        }
        return ResponseEntity.ok(ProviderClaimResponse.of(
                claimService.review(claimId, approve, actor(authentication), request.note())));
    }

    @DeleteMapping("/providers/me/claims/{claimId}")
    @Operation(summary = "Withdraw my own pending claim",
            description = "J14: the claimant's own recovery lever — WITHDRAWN frees the page "
                    + "for a fresh claim from anyone. Only the claim's own claimant may "
                    + "withdraw it (403 otherwise) and only while it is PENDING (409 once "
                    + "decided — an approved handover or a rejection is history, not a lever).")
    public ResponseEntity<Void> withdrawClaim(@PathVariable UUID claimId, Authentication authentication) {
        UUID claimantUserId = currentUserProvider.getCurrentUserId(authentication);
        claimService.withdraw(claimId, claimantUserId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin/provider-claims")
    @Operation(summary = "Read the business-claim review queue (admin)",
            description = "J14: the review board — the live claims by page and/or state "
                    + "(PENDING is the reviewable queue on its complete drain order: "
                    + "updatedAt ASC, id ASC — oldest pending claim first). Absent filters "
                    + "read the whole registry. Deterministic pagination; read-only.")
    public ResponseEntity<PagedResponse<ProviderClaimResponse>> claims(
            @Parameter(description = "Optional provider (business page) id filter")
            @RequestParam(required = false) UUID providerId,
            @Parameter(description = "Optional claim state filter — PENDING, APPROVED, "
                    + "REJECTED or WITHDRAWN")
            @RequestParam(required = false) ProviderClaimState state,
            Pageable pageable) {
        return ResponseEntity.ok(PagedResponse.of(
                claimService.claims(providerId, state, pageable)
                        .map(ProviderClaimResponse::of)));
    }

    /** The acting administrator's own attribution string (the identity SPI convention). */
    private static String actor(Authentication authentication) {
        return authentication != null ? authentication.getName() : null;
    }
}

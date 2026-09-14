package com.marketplace.messaging;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.PagedResponse;
import com.marketplace.shared.security.CurrentUserProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): the public lead
 * submission and the provider's inbox.
 *
 * <p><b>The public surface</b> ({@code POST /api/v1/listings/{id}/leads}):
 * no mandatory authentication — the plan's "بلا مصادقة إلزامية" (the guest
 * fills name and phone). The one-line permitAll in {@code SecurityConfig}
 * follows the webhooks precedent (a public POST is not a novelty in this
 * system); a presented-but-invalid bearer token still answers 401 through
 * the resource-server filter, and a valid one attributes the lead.
 *
 * <p><b>The "me" surface</b> ({@code /api/v1/providers/me/leads}): the
 * lead's provider column lives in the users.id space (the A1/V2 fact),
 * so the caller's user id IS the inbox key — the plain {@code /me} seam
 * every authenticated surface here uses, no profile indirection.
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
public class LeadsController {

    private final LeadsService leadsService;
    private final CurrentUserProvider currentUserProvider;

    public LeadsController(LeadsService leadsService,
                           CurrentUserProvider currentUserProvider) {
        this.leadsService = leadsService;
        this.currentUserProvider = currentUserProvider;
    }

    /**
     * Public submission — the L29 independent named rate-limiter instance
     * (timeout 0: fail fast with 429 RL-001), with the G-R6 daily cap
     * behind it in the service.
     */
    @PostMapping("/listings/{id}/leads")
    @RateLimiter(name = "leadCreate")
    @Operation(summary = "Submit a lead for a listing",
            description = "A contact message to the listing's provider — the mediated-contact model. "
                    + "Public: the guest supplies name, phone and message; a valid bearer token "
                    + "additionally attributes the lead to the sender's account. Rate-limited per "
                    + "instance window and per sender daily cap (429).")
    public ResponseEntity<LeadResponse> createLead(
            @PathVariable UUID id,
            @Valid @RequestBody LeadRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {
        String clientIp = httpRequest.getRemoteAddr();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(leadsService.createLead(id, request, authentication, clientIp));
    }

    @GetMapping("/providers/me/leads")
    @Operation(summary = "List my leads (provider inbox)",
            description = "Paginated leads for the calling provider — newest first, deterministic "
                    + "order. Optional status filter (NEW, READ, ARCHIVED).")
    public ResponseEntity<PagedResponse<LeadResponse>> myLeads(
            @Parameter(description = "Filter by inbox status — omitted returns all.")
            @RequestParam(required = false) LeadStatus status,
            Pageable pageable, Authentication authentication) {
        UUID ownerUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(PagedResponse.of(leadsService.listLeads(ownerUserId, status, pageable)));
    }

    @PatchMapping("/providers/me/leads/{leadId}")
    @Operation(summary = "Move a lead in the inbox",
            description = "One-way moves only: NEW→READ, NEW→ARCHIVED, READ→ARCHIVED. A foreign "
                    + "provider's lead is a 404 — it is not in your inbox.")
    public ResponseEntity<LeadResponse> transitionLead(
            @PathVariable UUID leadId,
            @Valid @RequestBody LeadTransitionRequest request,
            Authentication authentication) {
        UUID ownerUserId = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(leadsService.transitionLead(leadId, ownerUserId, request.status()));
    }

    /**
     * The inbox move's body — the target status only; anything outside the
     * enum fails bean validation with 400 before the service runs.
     */
    public record LeadTransitionRequest(
            @NotNull
            @Schema(description = "The target inbox status: READ or ARCHIVED.")
            LeadStatus status
    ) {
    }
}

package com.marketplace.disputes;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.api.DisputeResolution;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(value = ApiConstants.API_V1, version = "1.0")
@Validated
public class DisputeController {

    private final DisputeService service;
    private final DisputeMapper disputeMapper;

    public DisputeController(DisputeService service, DisputeMapper disputeMapper) {
        this.service = service;
        this.disputeMapper = disputeMapper;
    }

    /**
     * Plan item 2.7: the dispute's creation answers 201 Created — RFC 9110
     * §15.3.2 ("The 201 (Created) status code indicates that the request has
     * been fulfilled and has led to the creation of a new resource") — the
     * same in-repo creation precedent as {@code MessagingController
     * #createConversation}. The measured 200 was the wrong creation
     * semantics; the OpenAPI gate documents this intentional contract change
     * as a dated exception in {@code .ci/openapi-compat-allowlist.yml}
     * (removed when the next release tag re-baselines the gate).
     */
    @PostMapping("/bookings/{bookingId}/disputes")
    @Operation(summary = "Open a dispute on a booking",
            description = "Opens a dispute on the caller's own booking with a mandatory "
                    + "reason — the consumer-side entry into the dispute flow (OPEN state).")
    public ResponseEntity<DisputeResponse> open(@PathVariable UUID bookingId, @RequestParam @NotBlank String reason, Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED).body(disputeMapper.toResponse(service.open(bookingId, reason, authentication)));
    }

    @GetMapping("/bookings/{bookingId}/disputes")
    @Operation(summary = "List a booking's disputes",
            description = "The dispute trail of one booking — visibility-scoped to the "
                    + "booking's participants (consumer/provider) and administrators.")
    public ResponseEntity<List<DisputeResponse>> list(@PathVariable UUID bookingId, Authentication authentication) {
        List<DisputeResponse> disputes = service.listForBooking(bookingId, authentication).stream()
                .map(disputeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(disputes);
    }

    /**
     * ADR-0009 (plan D-09 closure — the ADR-0004 deferral): the loan
     * subject's opening — the booking endpoint's twin (the same creation
     * semantics: 201 Created, RFC 9110 §15.3.2 — a NEW path, so no gate
     * entry is needed; the baseline gains it additively). The party gate is
     * the loan's own (borrower/owner); the opened dispute's LOAN subject
     * freezes the loan's ACTIVE edges through the lending module's listener.
     */
    @PostMapping("/loans/{loanId}/disputes")
    @Operation(summary = "Open a dispute on a loan",
            description = "ADR-0009: opens a dispute on the caller's own loan (borrower or "
                    + "owner) with a mandatory reason — the lending workflow's damage-dispute "
                    + "entry. The loan freezes (DISPUTED) until the resolution; a "
                    + "REFUND_CONSUMER resolution settles the money through the loan's own "
                    + "cancellation chain (the existing payments engine).")
    public ResponseEntity<DisputeResponse> openLoan(@PathVariable UUID loanId,
                                                    @RequestParam @NotBlank String reason,
                                                    Authentication authentication) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(disputeMapper.toResponse(service.openForLoan(loanId, reason, authentication)));
    }

    @GetMapping("/loans/{loanId}/disputes")
    @Operation(summary = "List a loan's disputes",
            description = "ADR-0009: the dispute trail of one loan — visibility-scoped to "
                    + "the loan's parties (borrower/owner) and administrators.")
    public ResponseEntity<List<DisputeResponse>> listForLoan(@PathVariable UUID loanId,
                                                             Authentication authentication) {
        List<DisputeResponse> disputes = service.listForLoan(loanId, authentication).stream()
                .map(disputeMapper::toResponse)
                .toList();
        return ResponseEntity.ok(disputes);
    }

    /**
     * L24: the resolve decision carries the financial outcome — the body's
     * {@code resolution} selects REFUND_CONSUMER / RELEASE_PROVIDER /
     * NO_ACTION (roadmap §5). The body is OPTIONAL for backward
     * compatibility: a body-less call resolves with NO_ACTION — exactly the
     * endpoint's pre-L24 semantics (resolve, no money movement) — so the
     * public contract stays compatible (the OpenAPI gate) and money never
     * moves implicitly: REFUND_CONSUMER must be named explicitly.
     *
     * <p>B-06 (compliance plan 0.7): the optional {@code refundAmountCents}
     * activates the PARTIAL refund on the existing payments path. The
     * amount-less decision keeps the 3-arg call site byte-identical (Track
     * A's app-side WebMvcTest stubs couple to it — this garden's evolution
     * breaks no one else's build); the partial decision rides the 4-arg
     * path. Both converge in {@code DisputeService.resolve}.
     */
    @PostMapping("/admin/disputes/{id}/resolve")
    @Operation(summary = "Resolve a dispute (administrative)",
            description = "L24: the resolution selects the financial outcome — "
                    + "REFUND_CONSUMER / RELEASE_PROVIDER / NO_ACTION. The body is OPTIONAL "
                    + "for backward compatibility: a body-less call resolves with NO_ACTION "
                    + "(resolve, no money movement); REFUND_CONSUMER must be named explicitly "
                    + "— money never moves implicitly. B-06: on REFUND_CONSUMER the optional "
                    + "refundAmountCents activates the PARTIAL refund on the existing payments "
                    + "path (null = full refund); it is a 400 on any other resolution.")
    public ResponseEntity<DisputeResponse> resolve(@PathVariable UUID id,
                                                    @Valid @RequestBody(required = false) ResolveDisputeRequest request,
                                                    Authentication authentication) {
        DisputeResolution resolution = request == null ? DisputeResolution.NO_ACTION : request.resolution();
        if (request == null || request.refundAmountCents() == null) {
            return ResponseEntity.ok(disputeMapper.toResponse(service.resolve(id, resolution, authentication)));
        }
        return ResponseEntity.ok(disputeMapper.toResponse(
                service.resolve(id, resolution, request.refundAmountCents(), authentication)));
    }
}

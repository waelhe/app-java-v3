package com.marketplace.lending;

import com.marketplace.shared.api.ApiConstants;
import com.marketplace.shared.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Stage 8 (ADR-0004): the lending surface — the owner's offer lifecycle
 * and the loan machine's edges. The party gates live on the service (the
 * honest 404 for strangers); the fee is never caller-supplied (the
 * owner's own terms compute it).
 */
@RestController
@RequestMapping(value = ApiConstants.API_V1 + "/lending", version = "1.0")
public class LendingController {

    private final LendingService lendingService;
    private final CurrentUserProvider currentUserProvider;

    public LendingController(LendingService lendingService, CurrentUserProvider currentUserProvider) {
        this.lendingService = lendingService;
        this.currentUserProvider = currentUserProvider;
    }

    // -- the offer projection (the owner's terms) ------------------------

    @PostMapping("/offers/{productId}")
    @Operation(summary = "Publish (or update) the lending offer on your product",
            description = "The owner's own daily fee and optional deposit, minor units. "
                    + "The product must be ACTIVE and owned by the caller (a stranger's "
                    + "product answers the honest 404).")
    public ResponseEntity<OfferResponse> publishOffer(@PathVariable UUID productId,
                                                      @Valid @RequestBody PublishOfferRequest request,
                                                      Authentication authentication) {
        return ResponseEntity.ok(OfferResponse.of(
                lendingService.publishOffer(productId, request.dailyFeeMinor(),
                        request.depositMinor(), authentication)));
    }

    @DeleteMapping("/offers/{productId}")
    @Operation(summary = "Withdraw the lending offer")
    public ResponseEntity<Void> withdrawOffer(@PathVariable UUID productId, Authentication authentication) {
        lendingService.withdrawOffer(productId, authentication);
        return ResponseEntity.noContent().build();
    }

    // -- the machine (the borrower's and owner's edges) ------------------

    @PostMapping("/items/{productId}/loans")
    @Operation(summary = "Request a loan period",
            description = "The fee is the owner's own terms (the daily rate × the days) — "
                    + "never caller-supplied. The period's exclusivity is enforced twice: "
                    + "the transactional overlap check (the 409 with the message) and the "
                    + "EXCLUDE constraint (the race's backstop).")
    public ResponseEntity<LoanResponse> request(@PathVariable UUID productId,
                                                @Valid @RequestBody RequestLoanRequest request,
                                                Authentication authentication) {
        Loan loan = lendingService.request(productId,
                Instant.parse(request.startAt()), Instant.parse(request.endAt()), authentication);
        return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                .body(LoanResponse.of(loan));
    }

    @PostMapping("/loans/{id}/approve")
    @Operation(summary = "The owner accepts — the fee's intent opens (the existing engine)")
    public ResponseEntity<LoanResponse> approve(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.approve(id, authentication)));
    }

    @PostMapping("/loans/{id}/decline")
    @Operation(summary = "The owner refuses — terminal, no money ever moved")
    public ResponseEntity<LoanResponse> decline(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.decline(id, authentication)));
    }

    @DeleteMapping("/loans/{id}")
    @Operation(summary = "The borrower cancels — the money settles through the event")
    public ResponseEntity<LoanResponse> cancel(@PathVariable UUID id,
                                               @Valid @RequestBody CancelLoanRequest request,
                                               Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(
                lendingService.cancel(id, request.reason(), authentication)));
    }

    @PostMapping("/loans/{id}/handover")
    @Operation(summary = "The owner confirms the handover — legal only on the settled fee")
    public ResponseEntity<LoanResponse> handover(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.activate(id, authentication)));
    }

    @PostMapping("/loans/{id}/return-request")
    @Operation(summary = "The borrower initiates the return")
    public ResponseEntity<LoanResponse> requestReturn(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.requestReturn(id, authentication)));
    }

    @PostMapping("/loans/{id}/return-confirm")
    @Operation(summary = "The owner confirms the return")
    public ResponseEntity<LoanResponse> confirmReturn(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.confirmReturn(id, authentication)));
    }

    @PostMapping("/loans/{id}/close")
    @Operation(summary = "The owner settles and closes — terminal, the period is free")
    public ResponseEntity<LoanResponse> close(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.close(id, authentication)));
    }

    @GetMapping("/loans/{id}")
    @Operation(summary = "Read one loan", description = "Party-gated: the borrower, the owner, "
            + "or ADMIN — anyone else gets an honest 404.")
    public ResponseEntity<LoanResponse> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(LoanResponse.of(lendingService.getForUser(id, authentication)));
    }

    @GetMapping("/borrower")
    @Operation(summary = "The caller's own borrowing history")
    public ResponseEntity<Object> listAsBorrower(Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(lendingService.listMineAsBorrower(caller, pageable, authentication)
                .map(LoanResponse::of));
    }

    @GetMapping("/owner")
    @Operation(summary = "The caller's own lending history")
    public ResponseEntity<Object> listAsOwner(Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        return ResponseEntity.ok(lendingService.listMineAsOwner(caller, pageable, authentication)
                .map(LoanResponse::of));
    }

    // -- the wire shapes (data-minimized by construction) ----------------

    public record PublishOfferRequest(
            @NotNull @Positive Long dailyFeeMinor,
            @NotNull @Positive Long depositMinor) {
    }

    public record RequestLoanRequest(
            @NotBlank String startAt,
            @NotBlank String endAt) {
    }

    public record CancelLoanRequest(@NotBlank String reason) {
    }

    public record OfferResponse(UUID id, UUID productId, UUID ownerId,
                                long dailyFeeMinor, String currency, long depositMinor) {
        static OfferResponse of(LendingOffer offer) {
            return new OfferResponse(offer.getId(), offer.getProductId(), offer.getOwnerId(),
                    offer.getDailyFeeMinor(), offer.getCurrency(), offer.getDepositMinor());
        }
    }

    public record LoanResponse(UUID id, UUID productId, UUID ownerId, UUID borrowerId,
                               String status, String startAt, String endAt,
                               long feeMinor, String currency, UUID paymentIntentId) {
        static LoanResponse of(Loan loan) {
            return new LoanResponse(loan.getId(), loan.getProductId(), loan.getOwnerId(),
                    loan.getBorrowerId(), loan.getStatus().name(),
                    loan.getStartAt().toString(), loan.getEndAt().toString(),
                    loan.getFeeMinor(), loan.getCurrency(), loan.getPaymentIntentId());
        }
    }
}

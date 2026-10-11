package com.marketplace.lending;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.LoanApprovedEvent;
import com.marketplace.shared.api.LoanCancelledEvent;
import com.marketplace.shared.api.LoanClosedEvent;
import com.marketplace.shared.api.LoanPaymentPort;
import com.marketplace.shared.api.LoanRequestedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Stage 8 (plan D-09, ADR-0004): the lending machine — the offer
 * projection (the owner's own terms, separate from the confirmed loans)
 * and the loan workflow (request → approve → [pay via the EXISTING
 * engine] → handover → return → close), every transition guarded,
 * stamped, audited, and event-carrying (the A-11 order-machine
 * discipline verbatim).
 *
 * <p><b>The period's exclusivity (the plan's two-competing-borrowers
 * gate):</b> the request/approval transaction locks the item's offer row
 * FOR UPDATE (the official JPA pessimistic lock), runs the overlap query,
 * and the V174 EXCLUDE constraint stands behind both as the concurrency
 * backstop — the loser answers the house 409, never a partial hold.
 *
 * <p><b>The money (no escrow vocabulary):</b> the fee's intent rides the
 * EXISTING payments engine (the LOAN origin, the deterministic
 * {@code loan-…} key); the settlement's COMPLETED event marks the loan
 * paid; the cancellation's money is the {@code LoanCancelledEvent} → the
 * payments module's own listener (the order-cancellation contract
 * verbatim). The ledger credits the owner through {@code LoanOwnerPort}
 * with the SAME announced commission rate.
 *
 * <p><b>Privacy:</b> the loan's reads answer the buyer-or-owner-or-ADMIN
 * gate (the honest 404 otherwise — the A-03 precedent).
 */
@Service
public class LendingService {

    private final LendingOfferRepository offerRepository;
    private final LoanRepository loanRepository;
    private final ProductPricingPort productPricingPort;
    private final LoanPaymentPort loanPaymentPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public LendingService(LendingOfferRepository offerRepository,
                          LoanRepository loanRepository,
                          ProductPricingPort productPricingPort,
                          LoanPaymentPort loanPaymentPort,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher) {
        this.offerRepository = offerRepository;
        this.loanRepository = loanRepository;
        this.productPricingPort = productPricingPort;
        this.loanPaymentPort = loanPaymentPort;
        this.currentUserProvider = currentUserProvider;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------------
    // The offer projection (the owner's own terms)
    // ------------------------------------------------------------------

    /**
     * Publishes (or updates) the lending offer on the caller's own ACTIVE
     * product — the ownership gate is the product's own owner fact (the
     * pricing port's carrier), a stranger's product answers the honest 404.
     */
    @Transactional
    @Observed(name = "lending.offer.publish")
    public LendingOffer publishOffer(UUID productId, long dailyFeeMinor, long depositMinor,
                                     Authentication authentication) {
        ProductPricingPort.ProductPrice product = productPricingPort.priceOf(productId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!product.providerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Product", productId);
        }
        if (product.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
            throw new ConflictException("Product " + productId + " is " + product.storefront()
                    + " — offers ride ACTIVE products only");
        }
        LendingOffer offer = offerRepository.findByProductId(productId)
                .orElseGet(() -> LendingOffer.publish(productId, product.providerId(),
                        dailyFeeMinor, product.currency(), depositMinor));
        offer.updateTerms(dailyFeeMinor, depositMinor);
        return offerRepository.save(offer);
    }

    /** Withdraws the offer — the caller's own product (the same gate). */
    @Transactional
    public void withdrawOffer(UUID productId, Authentication authentication) {
        LendingOffer offer = requireOwnedOffer(productId, authentication);
        offerRepository.delete(offer);
    }

    private LendingOffer requireOwnedOffer(UUID productId, Authentication authentication) {
        LendingOffer offer = offerRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("LendingOffer", productId));
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!offer.getOwnerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("LendingOffer", productId);
        }
        return offer;
    }

    // ------------------------------------------------------------------
    // The machine
    // ------------------------------------------------------------------

    /**
     * The borrower's request — the serialization point (the offer row
     * locked FOR UPDATE, the overlap query answered before the INSERT;
     * the EXCLUDE constraint is the race's backstop). The fee is the
     * OWNER's terms (the offer's daily rate × the period's days) — never
     * caller-supplied (the ADR-0002 amount-source lesson applied here).
     */
    @Transactional
    @Observed(name = "lending.loan.request")
    public Loan request(UUID productId, Instant startAt, Instant endAt,
                        Authentication authentication) {
        LendingOffer offer = offerRepository.lockForProduct(productId)
                .orElseThrow(() -> new ResourceNotFoundException("LendingOffer", productId));
        ProductPricingPort.ProductPrice product = productPricingPort.priceOf(productId);
        if (product.storefront() != ProductPricingPort.StorefrontState.ACTIVE) {
            throw new ConflictException("Product " + productId + " is " + product.storefront()
                    + " — a loan rides an ACTIVE product");
        }
        if (loanRepository.countLiveOverlap(productId, startAt, endAt) > 0) {
            throw new ConflictException("The period overlaps a live hold on item " + productId
                    + " — pick another window");
        }
        UUID borrower = currentUserProvider.getCurrentUserId(authentication);
        if (borrower.equals(offer.getOwnerId())) {
            throw new ConflictException("The owner cannot borrow their own item");
        }
        long fee = offer.feeFor(startAt, endAt);
        Loan loan = loanRepository.save(Loan.request(productId, offer.getOwnerId(), borrower,
                startAt, endAt, fee, offer.getCurrency()));
        eventPublisher.publishEvent(new LoanRequestedEvent(loan.getId(), borrower, offer.getOwnerId()));
        return loan;
    }

    /**
     * The owner's acceptance — creates (or idempotently returns) the
     * fee's intent through the EXISTING engine and links it. The
     * approval transaction re-runs the overlap check (the other edge of
     * the race: two requests approved back-to-back).
     */
    @Transactional
    @Observed(name = "lending.loan.approve")
    public Loan approve(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireOwner(loan, authentication);
        loan.approve();
        // The DB row is still REQUESTED here (not live), so any positive
        // count is a REAL competing hold — the other edge of the race.
        if (loanRepository.countLiveOverlap(loan.getProductId(), loan.getStartAt(), loan.getEndAt()) > 0) {
            throw new ConflictException("The period overlaps another live hold on item "
                    + loan.getProductId() + " — the approval is refused");
        }
        Loan saved = loanRepository.save(loan);
        PaymentIntentDetails details = loanPaymentPort.createForLoan(
                loan.getId(), loan.getBorrowerId(), loan.getFeeMinor(), loan.getCurrency());
        if (!details.paymentIntentId().equals(loan.getPaymentIntentId())) {
            loan.linkPaymentIntent(details.paymentIntentId());
            saved = loanRepository.save(loan);
        }
        eventPublisher.publishEvent(new LoanApprovedEvent(loan.getId(), loan.getBorrowerId(), loan.getOwnerId()));
        return saved;
    }

    /** The owner's refusal — terminal, no money ever moved. */
    @Transactional
    public Loan decline(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireOwner(loan, authentication);
        loan.decline();
        return loanRepository.save(loan);
    }

    /**
     * The borrower's own escape hatch — the money settles through the
     * event (the payments listener cancels the unpaid intent or refunds
     * the collected one).
     */
    @Transactional
    @Observed(name = "lending.loan.cancel")
    public Loan cancel(UUID loanId, String reason, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!loan.getBorrowerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan", loanId);
        }
        loan.cancel(reason, Instant.now());
        Loan saved = loanRepository.save(loan);
        eventPublisher.publishEvent(new LoanCancelledEvent(loan.getId(), loan.getBorrowerId()));
        return saved;
    }

    /**
     * The settlement listener's write — the fee's COMPLETED state marks
     * the loan paid (the order auto-confirm's twin; idempotent by the
     * state guard).
     */
    @Transactional
    public void markPaidFromPayment(UUID paymentIntentId) {
        loanRepository.findByPaymentIntentId(paymentIntentId)
                .filter(loan -> loan.getStatus() == LoanStatus.APPROVED && loan.getPaidAt() == null)
                .ifPresent(loan -> {
                    loan.markPaid(Instant.now());
                    loanRepository.save(loan);
                });
    }

    /** The owner's handover confirmation — legal only on the PAID gate (the entity's own). */
    @Transactional
    public Loan activate(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireOwner(loan, authentication);
        loan.activate(Instant.now());
        return loanRepository.save(loan);
    }

    /** The borrower's return initiation. */
    @Transactional
    public Loan requestReturn(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireBorrower(loan, authentication);
        loan.requestReturn(Instant.now());
        return loanRepository.save(loan);
    }

    /** The owner's return confirmation. */
    @Transactional
    public Loan confirmReturn(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireOwner(loan, authentication);
        loan.confirmReturn(Instant.now());
        return loanRepository.save(loan);
    }

    /** The owner's settlement close — terminal, the period is free for rebooking. */
    @Transactional
    public Loan close(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        requireOwner(loan, authentication);
        loan.close(Instant.now());
        Loan saved = loanRepository.save(loan);
        eventPublisher.publishEvent(new LoanClosedEvent(
                loan.getId(), loan.getBorrowerId(), loan.getOwnerId()));
        return saved;
    }

    // ------------------------------------------------------------------
    // Reads (the party gate: the borrower, the owner, or ADMIN — honest 404)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Loan getForUser(UUID loanId, Authentication authentication) {
        Loan loan = requireLoan(loanId);
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!loan.getBorrowerId().equals(caller) && !loan.getOwnerId().equals(caller)
                && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan", loanId);
        }
        return loan;
    }

    @Transactional(readOnly = true)
    public Page<Loan> listMineAsBorrower(UUID borrowerId, Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!borrowerId.equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan");
        }
        return loanRepository.findByBorrowerIdOrderByCreatedAtDescIdDesc(borrowerId, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Loan> listMineAsOwner(UUID ownerId, Pageable pageable, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!ownerId.equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan");
        }
        return loanRepository.findByOwnerIdOrderByCreatedAtDescIdDesc(ownerId, pageable);
    }

    private Loan requireLoan(UUID loanId) {
        return loanRepository.findById(loanId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }

    private void requireOwner(Loan loan, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!loan.getOwnerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan", loan.getId());
        }
    }

    private void requireBorrower(Loan loan, Authentication authentication) {
        UUID caller = currentUserProvider.getCurrentUserId(authentication);
        if (!loan.getBorrowerId().equals(caller) && !currentUserProvider.isAdmin(authentication)) {
            throw new ResourceNotFoundException("Loan", loan.getId());
        }
    }
}

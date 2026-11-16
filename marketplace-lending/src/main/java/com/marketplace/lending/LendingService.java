package com.marketplace.lending;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.DisputeLookupPort;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.LoanApprovedEvent;
import com.marketplace.shared.api.LoanCancelledEvent;
import com.marketplace.shared.api.LoanClosedEvent;
import com.marketplace.shared.api.LoanDisputedEvent;
import com.marketplace.shared.api.LoanDisputeResolvedEvent;
import com.marketplace.shared.api.LoanPaymentPort;
import com.marketplace.shared.api.LoanRequestedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
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
 * and the V200 EXCLUDE constraint stands behind both as the concurrency
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
    private final DisputeLookupPort disputeLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationEventPublisher eventPublisher;

    public LendingService(LendingOfferRepository offerRepository,
                          LoanRepository loanRepository,
                          ProductPricingPort productPricingPort,
                          LoanPaymentPort loanPaymentPort,
                          DisputeLookupPort disputeLookupPort,
                          CurrentUserProvider currentUserProvider,
                          ApplicationEventPublisher eventPublisher) {
        this.offerRepository = offerRepository;
        this.loanRepository = loanRepository;
        this.productPricingPort = productPricingPort;
        this.loanPaymentPort = loanPaymentPort;
        this.disputeLookupPort = disputeLookupPort;
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
                                     long lateFeePerDayMinor, Authentication authentication) {
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
                        dailyFeeMinor, product.currency(), depositMinor, lateFeePerDayMinor));
        offer.updateTerms(dailyFeeMinor, depositMinor, lateFeePerDayMinor);
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
        // selfId = null: the request's own loan does not exist yet — nothing
        // of its own to exclude from the count (the self-exclusion law).
        if (loanRepository.countLiveOverlap(null, productId, startAt, endAt) > 0) {
            throw new ConflictException("The period overlaps a live hold on item " + productId
                    + " — pick another window");
        }
        UUID borrower = currentUserProvider.getCurrentUserId(authentication);
        if (borrower.equals(offer.getOwnerId())) {
            throw new ConflictException("The owner cannot borrow their own item");
        }
        long fee = offer.feeFor(startAt, endAt);
        Loan loan = loanRepository.save(Loan.request(productId, offer.getOwnerId(), borrower,
                startAt, endAt, fee, offer.getCurrency(), offer.getLateFeePerDayMinor()));
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
        // The overlap gate runs BEFORE the candidate is dirtied to APPROVED
        // (measured 2026-10-18, both edges of the race): a count query's
        // pre-query AUTO flush writes the managed entity's pending state
        // FIRST — with the approval already applied, a REAL competing hold
        // then surfaces as the exclusion constraint's integrity violation
        // (a 500) instead of this honest 409, and without the count's
        // self-exclusion the flushed candidate made the count see ITSELF
        // (every approval answered 409 against no competitor at all). A
        // clean context writes nothing before the count; the candidate is
        // REQUESTED here so the self-exclusion is the defensive second
        // layer; the DB's loans_live_period_exclusive stays the race's last
        // line BEHIND this gate.
        if (loanRepository.countLiveOverlap(loan.getId(), loan.getProductId(), loan.getStartAt(), loan.getEndAt()) > 0) {
            throw new ConflictException("The period overlaps another live hold on item "
                    + loan.getProductId() + " — the approval is refused");
        }
        loan.approve();
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
    // The dispute cycle (ADR-0009 — the ADR-0004 deferral closed): the
    // disputes module's shared events are the ONLY entry; the listener is
    // the single writer (the machine's guard is the idempotency).
    // ------------------------------------------------------------------

    /**
     * The freeze write — the dispute opened on the loan: ACTIVE edges
     * frozen (the ADR-0004 gate verbatim), the freeze fact published to
     * both parties (the event ADR-0004's javadoc promised, now real).
     * Idempotent by the state guard: a redelivered DisputeOpenedEvent for
     * an already-DISPUTED (or moved-past) loan is a silent no-op.
     *
     * <p><b>Row 15 (agent findings 2026-10-25) — the freeze tracks OPEN
     * disputes by identity:</b> the shared event carries the dispute's
     * own id, and the freeze is applied only while that dispute is STILL
     * open in the disputes module's committed state (the owner answers
     * through the {@link DisputeLookupPort} contract pair). A redelivered
     * open event for a dispute the administration already resolved —
     * possible under the documented resubmission machinery, whose retry
     * can land after the resolution's own event has been consumed — must
     * not re-freeze a resumed loan: a freeze no future resolution would
     * ever release.
     *
     * <p><b>Part-2 review round 2 (the full review's measured Minor):</b>
     * the loan is read under the row's write lock
     * ({@code LoanRepository.findByIdForUpdate}) — the state the guards
     * filter on is the row's committed truth, never a snapshot a
     * concurrent writer's commit is about to invalidate (the documented
     * resubmission machinery makes the interleaving real, not
     * theoretical).
     */
    @Transactional
    public void markDisputedFromDispute(UUID disputeId, UUID loanId) {
        loanRepository.findByIdForUpdate(loanId)
                .filter(loan -> loan.getStatus() == LoanStatus.ACTIVE)
                .filter(loan -> disputeLookupPort.isDisputeOpen(disputeId))
                .ifPresent(loan -> {
                    loan.markDisputed();
                    loanRepository.save(loan);
                    eventPublisher.publishEvent(new LoanDisputedEvent(
                            loan.getId(), loan.getBorrowerId(), loan.getOwnerId()));
                });
    }

    /**
     * The resolution write — the disputes module's decision applied to the
     * loan: REFUND_CONSUMER terminates the loan through the cancellation
     * edge (the published {@code LoanCancelledEvent} drives the payments
     * module's listener — the ONE refund contract; the money never moves
     * here), any other decision releases the freeze and resumes the ACTIVE
     * edge (the DisputeResolvedEvent outcome the notifications module
     * writes receipts from). Idempotent by the state guard.
     *
     * <p><b>Row 15 (agent findings 2026-10-25) — the release is held while
     * any open dispute still requires the freeze, and an approved refund
     * is never silently discarded:</b> the shared event carries the
     * dispute's own id, and the two failure modes the finding measured are
     * closed — (1) resolving ONE of several concurrent disputes no longer
     * resumes the loan: the non-refund branch releases only when NO other
     * open dispute remains (the dispute-owning module answers through the
     * {@link DisputeLookupPort} contract pair, the resolving dispute
     * discounted by identity), so a later approved refund still finds the
     * loan DISPUTED and its cancellation edge legal — the exact
     * discarded-refund sequence the finding reported; (2) the refund
     * branch stays terminal-first: an approved refund terminates the loan
     * regardless of other open disputes — the strongest decision wins, and
     * the remaining disputes' resolutions no-op on the terminal loan by
     * the state guard (nothing left to freeze or release).
     *
     * <p><b>Part-2 review round 2 (the full review's measured Minor):</b>
     * same serialization as the freeze — the loan is read under the row's
     * write lock, so the DISPUTED filter and the has-open-dispute read
     * both run on the locked committed truth.
     */
    @Transactional
    public void resolveDisputeFromDisputes(UUID disputeId, UUID loanId, DisputeResolution resolution) {
        // Row 15 (agent findings 2026-10-26 14:33 export, riding the
        // part-2 review round 2 lock): the REFUND branch applies to the
        // loan's CURRENT paid state — DISPUTED (the frozen shape), ACTIVE,
        // RETURN_REQUESTED, RETURNED (the dispute-opened-after-return-begin
        // shapes the ACTIVE-only freeze never froze; the dispute API
        // accepts them because its gate carries no state filter) and
        // APPROVED (the CodeRabbit review round on #741's part 4,
        // 2026-10-28: markPaid settles the money while the loan still
        // awaits handover — a paid-awaiting-activation loan is exactly
        // the paid state the dispute API accepts, and dropping it here
        // recorded the decision without executing it, after which
        // hasRefundResolutionForLoan held any later freeze's release
        // forever — the loan could die in DISPUTED). The pre-fix filter
        // answered only DISPUTED, so a full refund approved for a
        // returning loan was recorded but never executed — the
        // recommendation's own words: "Execute approved refunds for every
        // paid loan state on which disputes are accepted, independently of
        // whether a freeze occurred". The non-refund RELEASE stays
        // DISPUTED-only by design: there is nothing to release on a loan
        // the freeze never froze. The row's write lock (part-2 review
        // round 2 — the same serialization as the freeze) keeps the
        // filter and the has-open-dispute read on the locked committed
        // truth.
        loanRepository.findByIdForUpdate(loanId)
                .filter(loan -> resolution == DisputeResolution.REFUND_CONSUMER
                        ? loan.getStatus() == LoanStatus.DISPUTED
                                || loan.getStatus() == LoanStatus.ACTIVE
                                || loan.getStatus() == LoanStatus.RETURN_REQUESTED
                                || loan.getStatus() == LoanStatus.RETURNED
                                || loan.getStatus() == LoanStatus.APPROVED
                        : loan.getStatus() == LoanStatus.DISPUTED)
                .ifPresent(loan -> {
                    if (resolution == DisputeResolution.REFUND_CONSUMER) {
                        loan.cancelFromDispute("dispute refund resolution", Instant.now());
                        loanRepository.save(loan);
                        eventPublisher.publishEvent(new LoanCancelledEvent(
                                loan.getId(), loan.getBorrowerId()));
                    } else if (!disputeLookupPort.hasOpenDisputeForLoan(loanId, disputeId)
                            // Row 8 (agent findings 2026-10-26 14:33 export):
                            // the release is ALSO held while ANY refund
                            // resolution exists for the loan — applied or
                            // still awaiting its event's consumption. The
                            // delivery-mode hole the 2025-10-25 round could
                            // not see: a refund decision commits, its event
                            // sits in the registry's (re)submission queue,
                            // and a sibling non-refund decision's event
                            // lands FIRST — the release sees no OPEN
                            // dispute (both resolved), resumes the loan,
                            // and the refund event then arrives to a loan
                            // that is ACTIVE, not DISPUTED: the filter drops
                            // it, the approved refund is silently
                            // discarded. Holding the freeze lets the
                            // registry's resubmission deliver the
                            // termination the decision already carries; an
                            // APPLIED refund needs no hold (the loan is
                            // terminal — CANCELLED — and the filter above
                            // never reaches this branch).
                            && !disputeLookupPort.hasRefundResolutionForLoan(loanId)) {
                        // The LAST open dispute resolved — the freeze's exit.
                        loan.resumeFromDispute();
                        loanRepository.save(loan);
                        eventPublisher.publishEvent(new LoanDisputeResolvedEvent(
                                loan.getId(), loan.getBorrowerId(), loan.getOwnerId()));
                    }
                    // else: another open dispute still requires the freeze,
                    // or an approved refund awaits its delivery — the loan
                    // STAYS DISPUTED. The resolved dispute's own receipt
                    // already reached both parties through the disputes
                    // module's DisputeResolvedEvent consumers; no
                    // loan-state event lies about a resume that did not
                    // happen.
                });
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

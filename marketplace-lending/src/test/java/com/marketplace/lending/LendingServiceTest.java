package com.marketplace.lending;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.DisputeLookupPort;
import com.marketplace.shared.api.DisputeResolution;
import com.marketplace.shared.api.LoanApprovedEvent;
import com.marketplace.shared.api.LoanCancelledEvent;
import com.marketplace.shared.api.LoanDisputedEvent;
import com.marketplace.shared.api.LoanDisputeResolvedEvent;
import com.marketplace.shared.api.LoanPaymentPort;
import com.marketplace.shared.api.LoanRequestedEvent;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.ProductPricingPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Stage 8 (ADR-0004) — the lending machine's unit gate: the offer gate
 * (ownership + ACTIVE state), the fee's authoritative computation (the
 * owner's terms — never caller-supplied), the period's exclusivity (the
 * overlap 409), the machine's guarded edges (each stamped, the money legs
 * riding the existing engine), and the party-gated reads.
 */
@ExtendWith(MockitoExtension.class)
class LendingServiceTest {

    @Mock
    private LendingOfferRepository offerRepository;
    @Mock
    private LoanRepository loanRepository;
    @Mock
    private ProductPricingPort productPricingPort;
    @Mock
    private LoanPaymentPort loanPaymentPort;
    @Mock
    private DisputeLookupPort disputeLookupPort;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private Authentication authentication;

    private LendingService service;

    private final UUID owner = UUID.randomUUID();
    private final UUID borrower = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LendingService(offerRepository, loanRepository, productPricingPort,
                loanPaymentPort, disputeLookupPort, currentUserProvider, eventPublisher);
    }

    private void priceActive() {
        when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, owner, 149900L, "SAR",
                        ProductPricingPort.StorefrontState.ACTIVE));
    }

    private LendingOffer offer() {
        return LendingOffer.publish(productId, owner, 500L, "SAR", 1000L, 0L);
    }

    private Loan loan() {
        return Loan.request(productId, owner, borrower,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-04T10:00:00Z"),
                1500L, "SAR", 0L);
    }

    @Test
    void theFeeIsTheOwnersTermsNeverCallerSupplied() {
        LendingOffer offer = offer();
        when(offerRepository.lockForProduct(productId)).thenReturn(Optional.of(offer));
        priceActive();
        when(loanRepository.countLiveOverlap(isNull(), eq(productId), any(), any())).thenReturn(0L);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        // 3 days × 500 — computed from the OFFER, not from any caller input.
        Loan saved = service.request(productId,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-04T10:00:00Z"),
                authentication);

        assertThat(saved.getFeeMinor()).isEqualTo(1500L);
        assertThat(saved.getStatus()).isEqualTo(LoanStatus.REQUESTED);
        verify(eventPublisher).publishEvent(any(LoanRequestedEvent.class));
    }

    @Test
    void anOverlappingPeriodAnswersThe409BeforeAnyInsert() {
        when(offerRepository.lockForProduct(productId)).thenReturn(Optional.of(offer()));
        priceActive();
        when(loanRepository.countLiveOverlap(any(), any(), any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.request(productId,
                Instant.parse("2026-11-02T10:00:00Z"), Instant.parse("2026-11-05T10:00:00Z"),
                authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("overlaps");
        verify(loanRepository, never()).save(any());
    }

    @Test
    void theOwnerCannotBorrowTheirOwnItem() {
        when(offerRepository.lockForProduct(productId)).thenReturn(Optional.of(offer()));
        priceActive();
        when(loanRepository.countLiveOverlap(any(), any(), any(), any())).thenReturn(0L);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);

        assertThatThrownBy(() -> service.request(productId,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-02T10:00:00Z"),
                authentication))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("their own item");
    }

    @Test
    void aSuspendsWithTheOfferAndTheProductGate() {
        when(offerRepository.lockForProduct(productId)).thenReturn(Optional.of(offer()));
        when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, owner, 149900L, "SAR",
                        ProductPricingPort.StorefrontState.SUSPENDED));

        assertThatThrownBy(() -> service.request(productId,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-02T10:00:00Z"),
                authentication))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void theApprovalCreatesTheIntentThroughTheExistingEngineAndRechecksTheOverlap() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        when(loanRepository.countLiveOverlap(eq(loan.getId()), eq(productId), any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> service.approve(loan.getId(), authentication))
                .isInstanceOf(ConflictException.class);
        verify(loanPaymentPort, never()).createForLoan(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void theApprovalLinksTheIntentAndPublishesTheGate() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        when(loanRepository.countLiveOverlap(eq(loan.getId()), eq(productId), any(), any())).thenReturn(0L);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID intentId = UUID.randomUUID();
        when(loanPaymentPort.createForLoan(loan.getId(), borrower, 1500L, "SAR")).thenReturn(
                new PaymentIntentDetails(intentId, null, borrower, null, "CREATED", "LOAN",
                        1500L, "SAR", null, loan.getId(), null));

        Loan approved = service.approve(loan.getId(), authentication);

        assertThat(approved.getStatus()).isEqualTo(LoanStatus.APPROVED);
        assertThat(approved.getPaymentIntentId()).isEqualTo(intentId);
        verify(eventPublisher).publishEvent(any(LoanApprovedEvent.class));
    }

    @Test
    void theMachineRefusesTheIllegalEdges() {
        Loan requested = loan();

        assertThatThrownBy(() -> requested.activate(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> requested.markPaid(Instant.now()))
                .isInstanceOf(IllegalStateException.class);
        requested.approve();
        // The handover's paid gate: approved but unpaid — refused.
        assertThatThrownBy(() -> requested.activate(Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("settled fee");
    }

    @Test
    void theFullHappyPathStampsEveryEdge() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.parse("2026-10-15T09:00:00Z"));
        loan.activate(Instant.parse("2026-11-01T10:00:00Z"));
        loan.requestReturn(Instant.parse("2026-11-04T09:00:00Z"));
        loan.confirmReturn(Instant.parse("2026-11-04T12:00:00Z"));
        loan.close(Instant.parse("2026-11-04T13:00:00Z"));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThat(loan.getPaidAt()).isNotNull();
        assertThat(loan.isLive()).isFalse();
    }

    @Test
    void theCancellationReleasesThePeriodAndCarriesTheTruth() {
        Loan loan = loan();
        loan.approve();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        Loan cancelled = service.cancel(loan.getId(), "plans changed", authentication);

        assertThat(cancelled.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        ArgumentCaptor<LoanCancelledEvent> event = ArgumentCaptor.forClass(LoanCancelledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().borrowerId()).isEqualTo(borrower);
    }

    @Test
    void aStrangerGetsTheHonest404OnThePartyGate() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.getForUser(loan.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // ADR-0009 — the dispute cycle and the late-fee settlement
    // ------------------------------------------------------------------

    /**
     * The late-fee rules opened: the owner's per-day surcharge freezes on
     * the loan at request time (never caller-supplied), and the close edge
     * derives the lateness from the stamped returned_at — whole days, a
     * partial day rents the whole day (the feeFor ceiling verbatim).
     */
    @Test
    void theLateFeeIsTheOwnersFrozenTermsComputedAtSettlement() {
        Loan loan = Loan.request(productId, owner, borrower,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-04T10:00:00Z"),
                1500L, "SAR", 250L);
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.parse("2026-11-01T10:00:00Z"));
        loan.requestReturn(Instant.parse("2026-11-06T09:00:00Z"));
        // returned 2 days 2 hours past the end: ceiling → 3 late days.
        loan.confirmReturn(Instant.parse("2026-11-06T12:00:00Z"));
        loan.close(Instant.parse("2026-11-06T13:00:00Z"));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThat(loan.getLateDays()).isEqualTo(3);
        assertThat(loan.getLateFeeMinor()).isEqualTo(750L);
    }

    /** No surcharge declared → the settlement computes zero, whatever the lateness. */
    @Test
    void anOfferWithoutASurchargeSettlesZeroLateFee() {
        Loan loan = loan(); // late_fee_per_day_minor = 0
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.parse("2026-11-01T10:00:00Z"));
        loan.requestReturn(Instant.parse("2026-11-08T09:00:00Z"));
        loan.confirmReturn(Instant.parse("2026-11-08T12:00:00Z"));
        loan.close(Instant.parse("2026-11-08T13:00:00Z"));

        assertThat(loan.getLateDays()).isEqualTo(0);
        assertThat(loan.getLateFeeMinor()).isEqualTo(0L);
    }

    /** The freeze: the dispute opened on an ACTIVE loan enters DISPUTED and publishes the fact. */
    @Test
    void theDisputeFreezeEntersFromActiveAndPublishesTheFact() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        UUID disputeId = UUID.randomUUID();
        // part-2 review round 2: the dispute paths read under the row's write lock
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(disputeLookupPort.isDisputeOpen(disputeId)).thenReturn(true);

        service.markDisputedFromDispute(disputeId, loan.getId());

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISPUTED);
        assertThat(loan.isLive()).as("the DISPUTED loan stays in the live set — the period stays held").isTrue();
        ArgumentCaptor<LoanDisputedEvent> event = ArgumentCaptor.forClass(LoanDisputedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().loanId()).isEqualTo(loan.getId());
    }

    /** Idempotency by the state guard: a redelivered freeze for a closed loan is a no-op. */
    // ---- the offer's lifecycle (the owner's own terms desk) ----

    @Test
    void publishOffer_birthsTheOfferForTheOwningProvider() {
        priceActive();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        when(offerRepository.findByProductId(productId)).thenReturn(Optional.empty());
        when(offerRepository.save(any(LendingOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        LendingOffer published = service.publishOffer(productId, 500L, 1000L, 200L, authentication);

        assertThat(published.getOwnerId()).isEqualTo(owner);
        assertThat(published.getDailyFeeMinor()).isEqualTo(500L);
        assertThat(published.getLateFeePerDayMinor()).isEqualTo(200L);
        // The currency is the PRODUCT's own record (the ADR-0002 lesson:
        // the amount's source is the entity's truth, never the caller).
        assertThat(published.getCurrency()).isEqualTo("SAR");
    }

    @Test
    void publishOffer_updatesTheStandingOfferInPlace() {
        priceActive();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        LendingOffer standing = offer();
        when(offerRepository.findByProductId(productId)).thenReturn(Optional.of(standing));
        when(offerRepository.save(any(LendingOffer.class))).thenAnswer(inv -> inv.getArgument(0));

        service.publishOffer(productId, 700L, 1200L, 300L, authentication);

        assertThat(standing.getDailyFeeMinor()).isEqualTo(700L);
        assertThat(standing.getDepositMinor()).isEqualTo(1200L);
        assertThat(standing.getLateFeePerDayMinor()).isEqualTo(300L);
    }

    @Test
    void publishOffer_strangerGetsTheHonest404() {
        priceActive();
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThatThrownBy(() -> service.publishOffer(productId, 500L, 1000L, 0L, authentication))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(offerRepository);
    }

    @Test
    void publishOffer_ridesActiveProductsOnly() {
        when(productPricingPort.priceOf(productId)).thenReturn(
                new ProductPricingPort.ProductPrice(productId, owner, 149900L, "SAR",
                        ProductPricingPort.StorefrontState.SUSPENDED));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);

        assertThatThrownBy(() -> service.publishOffer(productId, 500L, 1000L, 0L, authentication))
                .isInstanceOf(ConflictException.class);
        verifyNoInteractions(offerRepository);
    }

    @Test
    void withdrawOffer_deletesTheOwnersStandingOffer() {
        LendingOffer standing = offer();
        when(offerRepository.findByProductId(productId)).thenReturn(Optional.of(standing));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);

        service.withdrawOffer(productId, authentication);

        verify(offerRepository).delete(standing);
    }

    @Test
    void theOwnerDeclinesTheRequestTerminalWithoutMoney() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.decline(loan.getId(), authentication);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DECLINED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ---- the dispute resolution's two branches (ADR-0009) ----

    @Test
    void theRefundResolutionTerminatesTheLoanAndPublishesTheCancellation() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(UUID.randomUUID(), loan.getId(), DisputeResolution.REFUND_CONSUMER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(LoanCancelledEvent.class));
    }

    /**
     * Row 15 (agent findings 2026-10-26 14:33 export): the refund executes
     * for EVERY paid state the dispute API accepts — a dispute opened
     * AFTER the return began addresses a RETURN_REQUESTED loan the
     * ACTIVE-only freeze never froze; the approved refund terminates it
     * exactly like a frozen loan's (the recommendation's own words:
     * "independently of whether a freeze occurred").
     */
    @Test
    void theDisputeRefundExecutesForAReturningLoanTheFreezeNeverFroze() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.requestReturn(Instant.now()); // RETURN_REQUESTED — mid-return
        UUID disputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(disputeId, loan.getId(), DisputeResolution.REFUND_CONSUMER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(LoanCancelledEvent.class));
    }

    /**
     * Row 8 (agent findings 2026-10-26 14:33 export): the release is HELD
     * while ANY refund resolution exists for the loan — the sibling refund
     * decision committed and awaits its event's consumption; releasing
     * first would drop it on arrival (the loan would no longer be
     * DISPUTED). The freeze stays; the registry's resubmission delivers
     * the termination the decision already carries.
     */
    @Test
    void theReleaseIsHeldWhileAnApprovedRefundResolutionExistsForTheLoan() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        UUID disputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(disputeLookupPort.hasOpenDisputeForLoan(loan.getId(), disputeId)).thenReturn(false);
        when(disputeLookupPort.hasRefundResolutionForLoan(loan.getId())).thenReturn(true);

        service.resolveDisputeFromDisputes(disputeId, loan.getId(), DisputeResolution.NO_ACTION);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISPUTED);
        verify(loanRepository, never()).save(any(Loan.class));
        verify(eventPublisher, never()).publishEvent(any(LoanDisputeResolvedEvent.class));
    }

    @Test
    void anyOtherResolutionReleasesTheFreezeAndPublishesTheReceiptFact() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        UUID disputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(disputeLookupPort.hasOpenDisputeForLoan(loan.getId(), disputeId)).thenReturn(false);

        service.resolveDisputeFromDisputes(disputeId, loan.getId(), DisputeResolution.RELEASE_PROVIDER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        verify(eventPublisher).publishEvent(any(LoanDisputeResolvedEvent.class));
    }

    // ---- the party-gated reads ----

    @Test
    void aPartyMemberReadsTheLoanAndAStrangerDoesNot() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);

        assertThat(service.getForUser(loan.getId(), authentication).getId()).isEqualTo(loan.getId());

        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.getForUser(loan.getId(), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theBorrowersListIsPartyGated() {
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);
        when(loanRepository.findByBorrowerIdOrderByCreatedAtDescIdDesc(eq(borrower), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listMineAsBorrower(borrower,
                org.springframework.data.domain.PageRequest.of(0, 20), authentication).isEmpty()).isTrue();

        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.listMineAsBorrower(borrower,
                org.springframework.data.domain.PageRequest.of(0, 20), authentication))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void theDisputeFreezeIsAStateGuardedNoOpForNonActiveLoans() {
        Loan loan = loan(); // REQUESTED
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));

        service.markDisputedFromDispute(UUID.randomUUID(), loan.getId());

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REQUESTED);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /** The release: a non-refund resolution resumes the ACTIVE edge and publishes the receipt. */
    @Test
    void theDisputeReleaseResumesTheLoanWithoutAnyMoneyMovement() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        UUID disputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        when(disputeLookupPort.hasOpenDisputeForLoan(loan.getId(), disputeId)).thenReturn(false);

        service.resolveDisputeFromDisputes(disputeId, loan.getId(), DisputeResolution.NO_ACTION);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        ArgumentCaptor<LoanDisputeResolvedEvent> event = ArgumentCaptor.forClass(LoanDisputeResolvedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().borrowerId()).isEqualTo(borrower);
    }

    /**
     * The refund resolution: the loan TERMINATES through the cancellation
     * edge and the published {@code LoanCancelledEvent} drives the ONE
     * refund contract (the payments module's listener) — the money never
     * moves here.
     */
    @Test
    void theDisputeRefundResolutionCancelsTheLoanAndRidesTheOneRefundContract() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(UUID.randomUUID(), loan.getId(), DisputeResolution.REFUND_CONSUMER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(loan.getCancelReason()).contains("dispute");
        ArgumentCaptor<LoanCancelledEvent> event = ArgumentCaptor.forClass(LoanCancelledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().loanId()).isEqualTo(loan.getId());
    }

    // ---- row 15 (agent findings 2026-10-25) — identity-aware freeze/release ----

    /**
     * The finding's core sequence: resolving ONE of two concurrent
     * disputes must NOT resume the loan — the freeze belongs to every open
     * dispute, and releasing it early is what used to make a later approved
     * refund silently unenforceable.
     */
    @Test
    void aNonRefundResolutionHoldsTheFreezeWhileAnotherDisputeIsOpen() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        UUID disputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(disputeLookupPort.hasOpenDisputeForLoan(loan.getId(), disputeId)).thenReturn(true);

        service.resolveDisputeFromDisputes(disputeId, loan.getId(), DisputeResolution.NO_ACTION);

        assertThat(loan.getStatus()).as("another open dispute still requires the freeze").isEqualTo(LoanStatus.DISPUTED);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /**
     * The refund stays terminal-first: an approved refund terminates the
     * loan even while another dispute is open — the strongest decision
     * wins, and the remaining dispute's resolution no-ops on the terminal
     * loan by the state guard.
     */
    @Test
    void aRefundResolutionStillTerminatesWhileAnotherDisputeIsOpen() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(UUID.randomUUID(), loan.getId(), DisputeResolution.REFUND_CONSUMER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        verify(eventPublisher).publishEvent(any(LoanCancelledEvent.class));
        // The refund branch never consults the open-dispute fact — the
        // terminal decision does not compete with the freeze.
        verifyNoInteractions(disputeLookupPort);
    }

    /**
     * The freeze tracks OPEN disputes by identity: a redelivered open event
     * for a dispute the administration already resolved must not re-freeze
     * a resumed loan — a freeze no future resolution would ever release.
     */
    @Test
    void theFreezeTracksOnlyStillOpenDisputes() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        UUID staleDisputeId = UUID.randomUUID();
        when(loanRepository.findByIdForUpdate(loan.getId())).thenReturn(Optional.of(loan));
        when(disputeLookupPort.isDisputeOpen(staleDisputeId)).thenReturn(false);

        service.markDisputedFromDispute(staleDisputeId, loan.getId());

        assertThat(loan.getStatus()).as("the already-resolved dispute requires no freeze").isEqualTo(LoanStatus.ACTIVE);
        verify(loanRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    /** The machine's edges stay paired: a direct cancel from DISPUTED stays illegal; the dedicated edges are the only paths. */
    @Test
    void theDisputeEdgesAreTheOnlyDisputedPaths() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(Instant.now());
        loan.activate(Instant.now());
        loan.markDisputed();

        assertThatThrownBy(() -> loan.cancel("direct", Instant.now()))
                .isInstanceOf(IllegalStateException.class);

        // The release fires once — the second resume is already illegal.
        loan.resumeFromDispute();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
        assertThatThrownBy(() -> loan.resumeFromDispute())
                .as("resume twice is illegal — the edge already fired")
                .isInstanceOf(IllegalStateException.class);

        // Re-frozen, the refund-cancel edge is the terminal path.
        loan.markDisputed();
        loan.cancelFromDispute("dispute refund resolution", Instant.now());
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(loan.isLive()).isFalse();
    }
}

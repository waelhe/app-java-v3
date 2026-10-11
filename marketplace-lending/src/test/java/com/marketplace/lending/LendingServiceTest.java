package com.marketplace.lending;

import com.marketplace.shared.api.ConflictException;
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
                loanPaymentPort, currentUserProvider, eventPublisher);
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
        when(loanRepository.countLiveOverlap(productId, any(), any())).thenReturn(0L);
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
        when(loanRepository.countLiveOverlap(any(), any(), any())).thenReturn(1L);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);

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
        when(loanRepository.countLiveOverlap(any(), any(), any())).thenReturn(0L);
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
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(borrower);

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
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(loanRepository.countLiveOverlap(productId, loan.getStartAt(), loan.getEndAt())).thenReturn(1L);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> service.approve(loan.getId(), authentication))
                .isInstanceOf(ConflictException.class);
        verify(loanPaymentPort, never()).createForLoan(any(), any(), org.mockito.ArgumentMatchers.anyLong(), any());
    }

    @Test
    void theApprovalLinksTheIntentAndPublishesTheGate() {
        Loan loan = loan();
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(owner);
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
        when(loanRepository.countLiveOverlap(productId, loan.getStartAt(), loan.getEndAt())).thenReturn(0L);
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));
        UUID intentId = UUID.randomUUID();
        when(loanPaymentPort.createForLoan(loan.getId(), borrower, 1500L, "SAR")).thenReturn(
                new PaymentIntentDetails(intentId, null, borrower, null, "CREATED", "LOAN",
                        1500L, "SAR", null, loan.getId()));

        Loan approved = service.approve(loan.getId(), authentication);

        assertThat(approved.getStatus()).isEqualTo(LoanStatus.APPROVED);
        assertThat(approved.getPaymentIntentId()).isEqualTo(intentId);
        verify(eventPublisher).publishEvent(any(LoanApprovedEvent.class));
    }

    @Test
    void theMachineRefusesTheIllegalEdges() {
        Loan requested = loan();

        assertThatThrownBy(requested::activate)
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
        when(currentUserProvider.isAdmin(authentication)).thenReturn(false);
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
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.markDisputedFromDispute(loan.getId());

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISPUTED);
        assertThat(loan.isLive()).as("the DISPUTED loan stays in the live set — the period stays held").isTrue();
        ArgumentCaptor<LoanDisputedEvent> event = ArgumentCaptor.forClass(LoanDisputedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().loanId()).isEqualTo(loan.getId());
    }

    /** Idempotency by the state guard: a redelivered freeze for a closed loan is a no-op. */
    @Test
    void theDisputeFreezeIsAStateGuardedNoOpForNonActiveLoans() {
        Loan loan = loan(); // REQUESTED
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));

        service.markDisputedFromDispute(loan.getId());

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
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(loan.getId(), DisputeResolution.NO_ACTION);

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
        when(loanRepository.findById(loan.getId())).thenReturn(Optional.of(loan));
        when(loanRepository.save(any(Loan.class))).thenAnswer(inv -> inv.getArgument(0));

        service.resolveDisputeFromDisputes(loan.getId(), DisputeResolution.REFUND_CONSUMER);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(loan.getCancelReason()).contains("dispute");
        ArgumentCaptor<LoanCancelledEvent> event = ArgumentCaptor.forClass(LoanCancelledEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().loanId()).isEqualTo(loan.getId());
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

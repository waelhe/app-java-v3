package com.marketplace.lending;

import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.LoanApprovedEvent;
import com.marketplace.shared.api.LoanCancelledEvent;
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
        return LendingOffer.publish(productId, owner, 500L, "SAR", 1000L);
    }

    private Loan loan() {
        return Loan.request(productId, owner, borrower,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-04T10:00:00Z"),
                1500L, "SAR");
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
}

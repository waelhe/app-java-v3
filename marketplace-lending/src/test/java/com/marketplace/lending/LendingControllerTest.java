package com.marketplace.lending;

import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage 8 (ADR-0004) — the wire layer's mapping gate: every endpoint
 * delegates to the service with the caller's own request (the fee never
 * rides the wire), the machine edges answer 200, the request answers 201,
 * the withdrawal answers 204, and the wire shapes are the service's own
 * facts (data-minimized by construction).
 */
@ExtendWith(MockitoExtension.class)
class LendingControllerTest {

    @Mock
    private LendingService lendingService;
    @Mock
    private CurrentUserProvider currentUserProvider;
    @Mock
    private Authentication authentication;

    private LendingController controller;

    private final UUID productId = UUID.randomUUID();
    private final UUID loanId = UUID.randomUUID();
    private final Instant start = Instant.parse("2026-11-01T10:00:00Z");
    private final Instant end = Instant.parse("2026-11-04T10:00:00Z");

    @BeforeEach
    void setUp() {
        controller = new LendingController(lendingService, currentUserProvider);
    }

    private Loan loan() {
        return Loan.request(productId, UUID.randomUUID(), UUID.randomUUID(),
                start, end, 1500L, "SAR", 0L);
    }

    @Test
    void publishOffer_mapsTheRequestTermsOntoTheService() {
        LendingOffer offer = LendingOffer.publish(productId, UUID.randomUUID(), 500L, "SAR", 1000L, 200L);
        when(lendingService.publishOffer(eq(productId), eq(500L), eq(1000L), eq(200L), eq(authentication)))
                .thenReturn(offer);

        ResponseEntity<LendingController.OfferResponse> result = controller.publishOffer(
                productId, new LendingController.PublishOfferRequest(500L, 1000L, 200L), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().dailyFeeMinor()).isEqualTo(500L);
        assertThat(result.getBody().lateFeePerDayMinor()).isEqualTo(200L);
    }

    @Test
    void publishOffer_treatsAnOmittedSurchargeAsZero() {
        LendingOffer offer = LendingOffer.publish(productId, UUID.randomUUID(), 500L, "SAR", 1000L, 0L);
        when(lendingService.publishOffer(eq(productId), eq(500L), eq(1000L), eq(0L), eq(authentication)))
                .thenReturn(offer);

        ResponseEntity<LendingController.OfferResponse> result = controller.publishOffer(
                productId, new LendingController.PublishOfferRequest(500L, 1000L, null), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void withdrawOfferAnswersNoContent() {
        ResponseEntity<Void> result = controller.withdrawOffer(productId, authentication);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(lendingService).withdrawOffer(productId, authentication);
    }

    @Test
    void theRequestAnswersCreatedWithTheOwnersTerms() {
        Loan loan = loan();
        when(lendingService.request(eq(productId), eq(start), eq(end), eq(authentication)))
                .thenReturn(loan);

        ResponseEntity<LendingController.LoanResponse> result = controller.request(
                productId, new LendingController.RequestLoanRequest(start.toString(), end.toString()),
                authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().feeMinor()).isEqualTo(1500L);
        assertThat(result.getBody().status()).isEqualTo("REQUESTED");
    }

    @Test
    void everyMachineEdgeAnswersOkAndDelegatesTheCaller() {
        Loan loan = loan();
        when(lendingService.approve(loanId, authentication)).thenReturn(loan);
        when(lendingService.decline(loanId, authentication)).thenReturn(loan);
        when(lendingService.activate(loanId, authentication)).thenReturn(loan);
        when(lendingService.requestReturn(loanId, authentication)).thenReturn(loan);
        when(lendingService.confirmReturn(loanId, authentication)).thenReturn(loan);
        when(lendingService.close(loanId, authentication)).thenReturn(loan);
        when(lendingService.getForUser(loanId, authentication)).thenReturn(loan);

        assertThat(controller.approve(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.decline(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.handover(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.requestReturn(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.confirmReturn(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.close(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.get(loanId, authentication).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(controller.get(loanId, authentication).getBody().status()).isEqualTo("REQUESTED");
    }

    @Test
    void theCancelCarriesTheReasonVerbatim() {
        Loan loan = loan();
        when(lendingService.cancel(eq(loanId), eq("changed my mind"), eq(authentication)))
                .thenReturn(loan);

        ResponseEntity<LendingController.LoanResponse> result = controller.cancel(
                loanId, new LendingController.CancelLoanRequest("changed my mind"), authentication);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void theListsMapThroughTheWireShape() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(currentUserProvider.getCurrentUserId(authentication)).thenReturn(UUID.randomUUID());
        when(lendingService.listMineAsBorrower(any(UUID.class), eq(pageable), eq(authentication)))
                .thenReturn(new PageImpl<>(List.of(loan()), pageable, 1));
        when(lendingService.listMineAsOwner(any(UUID.class), eq(pageable), eq(authentication)))
                .thenReturn(new PageImpl<>(List.of(loan()), pageable, 1));

        assertThat(((PageImpl<?>) controller.listAsBorrower(pageable, authentication).getBody()).getContent())
                .hasSize(1);
        assertThat(((PageImpl<?>) controller.listAsOwner(pageable, authentication).getBody()).getContent())
                .hasSize(1);
    }
}

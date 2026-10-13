package com.marketplace.lending;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 8 (ADR-0004) — the loan aggregate's own machine, measured edge by
 * edge: the request guards, the payment's paid gate (the handover is
 * illegal until the fee settled), the return's two-step, the ADR-0009
 * settlement's whole-day ceiling arithmetic priced by the OWNER's frozen
 * rate, the cancel's two legal sources, and the dispute freeze's paired
 * edges (resume and refund-cancel — the ADR-0009 additions).
 */
class LoanTest {

    private static final Instant START = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-10-03T00:00:00Z");

    private Loan loan() {
        return Loan.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                START, END, 1500L, "SAR", 200L);
    }

    // -- the request guards --

    @Test
    void thePeriodStartMustPrecedeItsEnd() {
        assertThatThrownBy(() -> Loan.request(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), END, START, 1500L, "SAR", 200L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLendingTermsAreNonNegative() {
        assertThatThrownBy(() -> Loan.request(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), START, END, 1500L, "SAR", -1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRequestIsBornRequestedWithItsFrozenTerms() {
        Loan loan = loan();
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.REQUESTED);
        assertThat(loan.getFeeMinor()).isEqualTo(1500L);
        assertThat(loan.getLateFeePerDayMinor()).isEqualTo(200L);
        // The live set holds the period from APPROVED onward — an
        // unapproved request holds nothing (the EXCLUDE constraint's own
        // read of isLive).
        assertThat(loan.isLive()).isFalse();
    }

    // -- the approve/decline edges --

    @Test
    void approveAndDeclineRideTheRequestedState() {
        Loan approved = loan();
        approved.approve();
        assertThat(approved.getStatus()).isEqualTo(LoanStatus.APPROVED);
        assertThat(approved.isLive()).isTrue();

        Loan declined = loan();
        declined.decline();
        assertThat(declined.getStatus()).isEqualTo(LoanStatus.DECLINED);
        assertThat(declined.isLive()).isFalse();
    }

    @Test
    void theWrongStateAnswersTheMachineError() {
        // approve/decline ride REQUESTED only: a settled loan (APPROVED
        // with the paid stamp) cannot decline, and a declined loan cannot
        // approve — the machine's own message names the legal source.
        Loan approved = loan();
        approved.approve();
        assertThatThrownBy(approved::decline)
                .isInstanceOf(IllegalStateException.class);

        Loan declined = loan();
        declined.decline();
        assertThatThrownBy(declined::approve)
                .isInstanceOf(IllegalStateException.class);
    }

    // -- the paid gate and the handover --

    @Test
    void thePaymentSettlementIsLegalFromApprovedOnly() {
        Loan loan = loan();
        Instant paidAt = START.minus(1, ChronoUnit.DAYS);
        assertThatThrownBy(() -> loan.markPaid(paidAt))
                .isInstanceOf(IllegalStateException.class);

        loan.approve();
        loan.markPaid(paidAt);
        assertThat(loan.getPaidAt()).isEqualTo(paidAt);
    }

    @Test
    void theHandoverRequiresTheSettledFee() {
        Loan loan = loan();
        loan.approve();
        // Unpaid APPROVED — the honest refusal.
        assertThatThrownBy(() -> loan.activate(START))
                .isInstanceOf(IllegalStateException.class);

        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    // -- the return's two steps --

    @Test
    void theReturnWalksTwoStepsAndStampsTheReturnedAt() {
        Loan loan = loan();
        loan.approve();
        assertThatThrownBy(() -> loan.requestReturn(START))
                .isInstanceOf(IllegalStateException.class);

        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.requestReturn(END);
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.RETURN_REQUESTED);

        loan.confirmReturn(END.plus(6, ChronoUnit.HOURS));
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.RETURNED);
    }

    // -- the ADR-0009 settlement arithmetic --

    @Test
    void anOnTimeSettlementMovesNoLateFee() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.requestReturn(END);
        loan.confirmReturn(END);

        loan.close(END.plus(1, ChronoUnit.HOURS));

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CLOSED);
        assertThat(loan.getLateDays()).isZero();
        assertThat(loan.getLateFeeMinor()).isZero();
    }

    @Test
    void aPartialLateDayRentsTheWholeDayAtTheOwnersFrozenRate() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.requestReturn(END);
        // Six hours past the period — one whole late day (the offer's own
        // ceiling semantics), priced 1 × the frozen 200.
        loan.confirmReturn(END.plus(6, ChronoUnit.HOURS));

        loan.close(END.plus(7, ChronoUnit.HOURS));

        assertThat(loan.getLateDays()).isEqualTo(1);
        assertThat(loan.getLateFeeMinor()).isEqualTo(200L);
    }

    @Test
    void aZeroSurchargeRatePricesNoLateFee() {
        Loan loan = Loan.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                START, END, 1500L, "SAR", 0L);
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.requestReturn(END);
        loan.confirmReturn(END.plus(2, ChronoUnit.DAYS));

        loan.close(END.plus(2, ChronoUnit.DAYS));

        assertThat(loan.getLateDays()).isZero();
        assertThat(loan.getLateFeeMinor()).isZero();
    }

    // -- the cancel's two legal sources --

    @Test
    void cancelIsLegalFromRequestedOrApprovedAndStampsTheReason() {
        Loan requested = loan();
        requested.cancel("changed my mind", START);
        assertThat(requested.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(requested.getCancelReason()).isEqualTo("changed my mind");
        assertThat(requested.isLive()).isFalse();

        Loan approved = loan();
        approved.approve();
        approved.cancel("borrower withdrew", START);
        assertThat(approved.getStatus()).isEqualTo(LoanStatus.CANCELLED);
    }

    @Test
    void cancelIsIllegalOnceTheItemChangedHands() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);

        assertThatThrownBy(() -> loan.cancel("too late", START))
                .isInstanceOf(IllegalStateException.class);
    }

    // -- the ADR-0009 dispute freeze's paired edges --

    @Test
    void theDisputeFreezeIsLegalFromActiveOnly() {
        Loan loan = loan();
        assertThatThrownBy(loan::markDisputed)
                .isInstanceOf(IllegalStateException.class);

        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.markDisputed();
        // ADR-0009: the DISPUTED loan stays in the live set (the item is
        // with the borrower — its period stays held).
        assertThat(loan.getStatus()).isEqualTo(LoanStatus.DISPUTED);
        assertThat(loan.isLive()).isTrue();
    }

    @Test
    void theReleaseResumesTheFrozenEdge() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.markDisputed();

        loan.resumeFromDispute();

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.ACTIVE);
    }

    @Test
    void theRefundResolutionTerminatesTheLoanWithItsReason() {
        Loan loan = loan();
        loan.approve();
        loan.markPaid(START.minus(1, ChronoUnit.DAYS));
        loan.activate(START);
        loan.markDisputed();

        loan.cancelFromDispute("ADR-0009 refund decision", END);

        assertThat(loan.getStatus()).isEqualTo(LoanStatus.CANCELLED);
        assertThat(loan.getCancelReason()).isEqualTo("ADR-0009 refund decision");
        assertThat(loan.isLive()).isFalse();
    }
}

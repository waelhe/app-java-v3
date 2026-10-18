package com.marketplace.payments;

import com.marketplace.payments.spi.SettlementEligibilityReport;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.LedgerAllocationPort;
import com.marketplace.shared.api.LedgerReadPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W11 — the settlement-eligibility law's unit proof: per settled credit,
 * the derivation ADR-0013 D3 names, with the HONEST reason for everything
 * the code cannot know. The measured absences are asserted as absences
 * (the ORDER's FULFILLED fact is not consulted because NO port carries
 * it), never papered over.
 */
class SettlementEligibilityServiceTest {

    private final LedgerReadPort ledgerReadPort = mock(LedgerReadPort.class);
    private final PaymentIntentRepository paymentIntentRepository = mock(PaymentIntentRepository.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);

    private final SettlementEligibilityService service = new SettlementEligibilityService(
            ledgerReadPort, paymentIntentRepository, bookingParticipantProvider);

    private static final UUID PROVIDER = UUID.randomUUID();
    private static final UUID CONSUMER = UUID.randomUUID();
    private static final Pageable PAGE = PageRequest.of(0, 50);

    // ------------------------------------------------------------------
    // The law per origin — the honest reasons
    // ------------------------------------------------------------------

    @Test
    void bookingCompleted_isEligible() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = paidBooking(bookingId);
        settledCredit(intent);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(booking(bookingId, "COMPLETED"));

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).as("ADR-0013 D3: a booking's eligibility is its COMPLETED state").isTrue();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
        assertThat(row.subjectId()).isEqualTo(bookingId);
        assertThat(row.origin()).isEqualTo("BOOKING");
        assertThat(report.totals()).singleElement()
                .satisfies(total -> {
                    assertThat(total.origin()).isEqualTo("BOOKING");
                    assertThat(total.currency()).isEqualTo("SAR");
                    assertThat(total.creditCount()).isEqualTo(1);
                    assertThat(total.creditedCents()).isEqualTo(5000L);
                    assertThat(total.eligibleCents()).isEqualTo(5000L);
                });
    }

    @Test
    void bookingStillConfirmed_domainEndNotReached() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = paidBooking(bookingId);
        settledCredit(intent);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(booking(bookingId, "CONFIRMED"));

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).as("collected but the service has not completed").isFalse();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_DOMAIN_END_NOT_REACHED);
        assertThat(report.totals()).singleElement()
                .satisfies(total -> assertThat(total.eligibleCents()).isZero());
    }

    @Test
    void bookingUnreadable_domainEndUnknown() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = paidBooking(bookingId);
        settledCredit(intent);
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenThrow(new ResourceNotFoundException("Booking", bookingId));

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        assertThat(onlyRow(report).reason())
                .isEqualTo(SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
    }

    @Test
    void orderFulfilledFactNotReadable_theHonestMeasuredAbsence() {
        // ADR-0013 D3 names the order's FULFILLED state as its eligibility
        // edge — and no port carries it here (OrderSellerPort answers
        // only the seller). Even a PAID order honestly answers UNKNOWN:
        // the derivation never consults an order fact, because none
        // exists to consult.
        UUID orderId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, CONSUMER,
                3000L, "SAR", "order-" + orderId, new BigDecimal("0.10"));
        intent.markProcessing();
        intent.markSucceeded();
        settledCredit(intent);

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).as("not eligible on this read's own authority").isFalse();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
        assertThat(row.subjectId()).isEqualTo(orderId);
    }

    @Test
    void loanFeeSettlesOnCollection_isEligible() {
        // ADR-0013 D3's own words: "a loan fee settles on collection" —
        // the collected, unrefunded fee needs no further domain fact.
        UUID loanId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForLoan(loanId, CONSUMER,
                1500L, "SAR", "loan-" + loanId, new BigDecimal("0.10"));
        intent.markProcessing();
        intent.markSucceeded();
        settledCredit(intent);

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).isTrue();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
        assertThat(row.subjectId()).isEqualTo(loanId);
    }

    @Test
    void adChargedWindow_isEligible() {
        // The AD-origin intent IS the charged window's bill — the billing
        // run freezes a window that already ran, then issues this intent.
        UUID campaignId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForAds(PROVIDER, campaignId,
                800L, "SAR", "ad-debit-" + campaignId + "-2026-10-18", null);
        intent.markProcessing();
        intent.markSucceeded();
        settledCredit(intent);

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).isTrue();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_SETTLED_NOT_REFUNDED);
        assertThat(row.subjectId()).isEqualTo(campaignId);
    }

    @Test
    void refundedIntent_isNotEligible() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent intent = paidBooking(bookingId);
        intent.markRefunded();
        settledCredit(intent);

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).isFalse();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_REFUNDED);
        assertThat(row.refundedAmountCents()).isEqualTo(5000L);
        // The booking seam is only consulted where it can change the
        // answer — a refunded credit never reaches it.
        verify(bookingParticipantProvider, never()).getBookingInfo(any());
    }

    @Test
    void partiallyRefundedIntent_isNotEligible_andCarriesTheRemainder() {
        // ADR-0013 D3's deliberate ledger no-op: the partial refund moves
        // nothing in the ledger, so the remainder's settlement treatment
        // is the owner's decision — the row carries both amounts, and the
        // read invents no arithmetic for the remainder.
        UUID orderId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.createForOrder(orderId, CONSUMER,
                3000L, "SAR", "order-" + orderId, new BigDecimal("0.10"));
        intent.markProcessing();
        intent.markSucceeded();
        intent.markPartiallyRefunded(1000L);
        settledCredit(intent);

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).isFalse();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_PARTIALLY_REFUNDED);
        assertThat(row.creditedCents()).isEqualTo(3000L);
        assertThat(row.refundedAmountCents()).isEqualTo(1000L);
    }

    @Test
    void creditWithoutIntentRow_theHonestDefensiveBranch() {
        // Payment intents have no delete path; the branch is total, not
        // expected — it answers UNKNOWN, never a guess.
        UUID orphanIntentId = UUID.randomUUID();
        when(ledgerReadPort.findCreditsByProvider(PROVIDER)).thenReturn(List.of(
                new LedgerReadPort.SettlementCredit(PROVIDER, orphanIntentId, 100L, "SAR", Instant.now())));
        when(paymentIntentRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(ledgerReadPort.findBalancesByProvider(PROVIDER)).thenReturn(List.of());

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        SettlementEligibilityReport.SettlementEligibilityRow row = onlyRow(report);
        assertThat(row.eligible()).isFalse();
        assertThat(row.reason()).isEqualTo(SettlementEligibilityReport.REASON_DOMAIN_END_UNKNOWN);
    }

    // ------------------------------------------------------------------
    // The composition — filters, totals, balances, paging
    // ------------------------------------------------------------------

    @Test
    void originFilterNarrowsAndUnknownOriginWordAnswersEmpty() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent bookingIntent = paidBooking(bookingId);
        PaymentIntent orderIntent = PaymentIntent.createForOrder(UUID.randomUUID(), CONSUMER,
                3000L, "SAR", "order-x", new BigDecimal("0.10"));
        orderIntent.markProcessing();
        orderIntent.markSucceeded();
        when(ledgerReadPort.findCreditsByProvider(PROVIDER)).thenReturn(List.of(
                credit(bookingIntent), credit(orderIntent)));
        when(paymentIntentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(bookingIntent, orderIntent));
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(booking(bookingId, "COMPLETED"));

        SettlementEligibilityReport ordersOnly = service.eligibility(PROVIDER, "ORDER", PAGE);
        assertThat(ordersOnly.credits().totalElements()).isEqualTo(1);
        assertThat(ordersOnly.credits().content().get(0).origin()).isEqualTo("ORDER");
        assertThat(ordersOnly.totals()).singleElement()
                .satisfies(total -> assertThat(total.origin()).isEqualTo("ORDER"));

        // The admin search's own rule: an unknown origin word honestly
        // answers the empty report — origins the running system does not
        // know cannot have rows.
        SettlementEligibilityReport unknown = service.eligibility(PROVIDER, "NOT_AN_ORIGIN", PAGE);
        assertThat(unknown.credits().totalElements()).isZero();
        assertThat(unknown.credits().content()).isEmpty();
        assertThat(unknown.totals()).isEmpty();
    }

    @Test
    void totalsArePerOriginAndCurrency_andBalancesRide() {
        UUID sarBookingId = UUID.randomUUID();
        UUID usdBookingId = UUID.randomUUID();
        PaymentIntent bookingIntent = paidBooking(sarBookingId);          // 5000 SAR, completed
        PaymentIntent usdBooking = PaymentIntent.create(usdBookingId, CONSUMER,
                7000L, "USD", "usd-booking", new BigDecimal("0.10")); // 7000 USD, still confirmed
        usdBooking.markProcessing();
        usdBooking.markSucceeded();
        PaymentIntent loanIntent = PaymentIntent.createForLoan(UUID.randomUUID(), CONSUMER,
                1500L, "SAR", "loan-x", new BigDecimal("0.10"));
        loanIntent.markProcessing();
        loanIntent.markSucceeded();
        when(ledgerReadPort.findCreditsByProvider(PROVIDER)).thenReturn(List.of(
                credit(bookingIntent), credit(usdBooking), credit(loanIntent)));
        when(paymentIntentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(bookingIntent, usdBooking, loanIntent));
        when(bookingParticipantProvider.getBookingInfo(sarBookingId))
                .thenReturn(booking(sarBookingId, "COMPLETED"));
        when(bookingParticipantProvider.getBookingInfo(usdBookingId))
                .thenReturn(booking(usdBookingId, "CONFIRMED"));
        when(ledgerReadPort.findBalancesByProvider(PROVIDER)).thenReturn(List.of(
                new LedgerAllocationPort.ProviderBalanceFact(PROVIDER, "SAR", 4500L),
                new LedgerAllocationPort.ProviderBalanceFact(PROVIDER, "USD", 6300L)));

        SettlementEligibilityReport report = service.eligibility(PROVIDER, null, PAGE);

        assertThat(report.balances()).hasSize(2);
        assertThat(report.totals()).extracting(
                        total -> total.origin() + ":" + total.currency())
                .containsExactly("BOOKING:SAR", "BOOKING:USD", "LOAN:SAR");
        assertThat(report.totals().get(0).eligibleCents()).isEqualTo(5000L);
        assertThat(report.totals().get(1).eligibleCents()).as("the USD booking is not COMPLETED").isZero();
        assertThat(report.totals().get(2).eligibleCents()).isEqualTo(1500L);
        assertThat(report.credits().totalElements()).isEqualTo(3);
    }

    @Test
    void thePageIsTheDeterministicNewestFirstSlice() {
        UUID bookingId = UUID.randomUUID();
        PaymentIntent first = paidBooking(bookingId);
        PaymentIntent second = paidBooking(bookingId);
        when(ledgerReadPort.findCreditsByProvider(PROVIDER)).thenReturn(List.of(
                credit(second, Instant.parse("2026-10-20T10:00:00Z")),
                credit(first, Instant.parse("2026-10-18T10:00:00Z"))));
        when(paymentIntentRepository.findAllById(anyCollection()))
                .thenReturn(List.of(first, second));
        when(bookingParticipantProvider.getBookingInfo(bookingId))
                .thenReturn(booking(bookingId, "COMPLETED"));

        SettlementEligibilityReport pageOne = service.eligibility(PROVIDER, null, PageRequest.of(0, 1));
        assertThat(pageOne.credits().content()).singleElement()
                .satisfies(row -> assertThat(row.paymentIntentId()).isEqualTo(second.getId()));
        assertThat(pageOne.credits().totalElements()).isEqualTo(2);
        assertThat(pageOne.credits().last()).isFalse();

        SettlementEligibilityReport pageTwo = service.eligibility(PROVIDER, null, PageRequest.of(1, 1));
        assertThat(pageTwo.credits().content()).singleElement()
                .satisfies(row -> assertThat(row.paymentIntentId()).isEqualTo(first.getId()));
        assertThat(pageTwo.credits().last()).isTrue();

        // The totals are over the provider's WHOLE credit set, not only
        // the page — the operational question is the whole worklist.
        assertThat(pageOne.totals()).singleElement()
                .satisfies(total -> assertThat(total.creditCount()).isEqualTo(2));
    }

    @Test
    void missingProviderId_answersTheHouse400() {
        assertThatThrownBy(() -> service.eligibility(null, null, PAGE))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("providerId");
    }

    // ------------------------------------------------------------------
    // The fixtures
    // ------------------------------------------------------------------

    private PaymentIntent paidBooking(UUID bookingId) {
        PaymentIntent intent = PaymentIntent.create(bookingId, CONSUMER,
                5000L, "SAR", "booking-" + bookingId, new BigDecimal("0.10"));
        intent.markProcessing();
        intent.markSucceeded();
        return intent;
    }

    private BookingInfo booking(UUID bookingId, String status) {
        return new BookingInfo(PROVIDER, CONSUMER, status, 5000L, "SAR",
                Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-02T10:00:00Z"));
    }

    private void settledCredit(PaymentIntent intent) {
        when(ledgerReadPort.findCreditsByProvider(PROVIDER)).thenReturn(List.of(credit(intent)));
        when(paymentIntentRepository.findAllById(anyCollection())).thenReturn(List.of(intent));
        when(ledgerReadPort.findBalancesByProvider(PROVIDER)).thenReturn(List.of());
    }

    private LedgerReadPort.SettlementCredit credit(PaymentIntent intent) {
        return credit(intent, Instant.now());
    }

    private LedgerReadPort.SettlementCredit credit(PaymentIntent intent, Instant creditedAt) {
        return new LedgerReadPort.SettlementCredit(
                PROVIDER, intent.getId(), intent.getAmountCents(), intent.getCurrency(), creditedAt);
    }

    private SettlementEligibilityReport.SettlementEligibilityRow onlyRow(SettlementEligibilityReport report) {
        assertThat(report.credits().content()).hasSize(1);
        return report.credits().content().get(0);
    }
}

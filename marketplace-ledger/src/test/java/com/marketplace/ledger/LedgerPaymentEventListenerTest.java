package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.LoanOwnerPort;
import com.marketplace.shared.api.OrderSellerPort;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LedgerPaymentEventListenerTest {

    private static final double COMMISSION_RATE = 0.10;

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final OrderSellerPort orderSellerPort = org.mockito.Mockito.mock(OrderSellerPort.class);
    private final LoanOwnerPort loanOwnerPort = org.mockito.Mockito.mock(LoanOwnerPort.class);

    private final LedgerPaymentEventListener listener = new LedgerPaymentEventListener(
            ledgerService, paymentIntentLookupPort, bookingParticipantProvider, orderSellerPort,
            loanOwnerPort, COMMISSION_RATE);

    @Test
    void ignoresNonCompletedEvents() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "PENDING");
        listener.onPaymentCompleted(event);
        verifyNoInteractions(paymentIntentLookupPort, bookingParticipantProvider, ledgerService);
    }

    @Test
    void ignoresPartiallyRefundedEvents() {
        // L24: only a FULL refund mirrors the credit (the dispute decision's
        // shape); partial PSP refunds are the payments side's bookkeeping.
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "PARTIALLY_REFUNDED");
        listener.onPaymentCompleted(event);
        verifyNoInteractions(paymentIntentLookupPort, bookingParticipantProvider, ledgerService);
    }

    @Test
    void debitsLedgerOnRefundedPayment() {
        // L24 acceptance 2: the refund debit mirrors the original credit —
        // the same priceCents the credit used, same booking source.
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        long priceCents = 5000L;
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "REFUNDED", "BOOKING", 25000L, "SAR", null, null, null);
        var bookingInfo = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED",
                priceCents, "USD", Instant.now(), Instant.now());

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromRefund(providerId, paymentIntentId, priceCents, "USD");
        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
        verify(ledgerService, never()).debitFromCommission(any(), any(), anyLong(), any());
    }

    @Test
    void creditsLedgerOnCompletedPayment() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        long priceCents = 5000L;
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        var bookingInfo = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED",
                priceCents, "USD", Instant.now(), Instant.now());

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        listener.onPaymentCompleted(event);

        // R9: the booking's currency rides EVERY money call — the pre-fix
        // listener was measured to ignore the field it already had.
        verify(ledgerService).creditFromPayment(providerId, paymentIntentId, priceCents, "USD");
        verify(ledgerService).debitFromCommission(providerId, paymentIntentId,
                BigDecimal.valueOf(priceCents)
                        .multiply(BigDecimal.valueOf(COMMISSION_RATE))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                "USD");
    }

    @Test
    void throwsWhenBookingLookupFails() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenThrow(new RuntimeException("lookup failed"));

        assertThrows(RuntimeException.class, () -> listener.onPaymentCompleted(event));

        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
    }

    @Test
    void propagatesExceptionWhenDebitFailsAfterCredit() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        long priceCents = 5000L;
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        var bookingInfo = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED",
                priceCents, "USD", Instant.now(), Instant.now());

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);
        doThrow(new RuntimeException("DB connection lost"))
                .when(ledgerService).debitFromCommission(any(), any(), anyLong(), any());

        assertThrows(RuntimeException.class, () -> listener.onPaymentCompleted(event));

        verify(ledgerService).creditFromPayment(providerId, paymentIntentId, priceCents, "USD");
        verify(ledgerService).debitFromCommission(eq(providerId), eq(paymentIntentId), anyLong(), eq("USD"));
    }

    // ---- W5 (yelp-level plan §5 — G24): the ad-origin branches ----

    @Test
    void creditsTheRawAmountForAnAdOriginCompletionWithNoCommission() {
        // W5: an ad bill's settlement — the payer IS the provider, the amount
        // rides the widened details, and NO commission and NO booking lookup
        // happen (the booking path's own machinery stays untouched).
        UUID paymentIntentId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, providerId, UUID.randomUUID(), "COMPLETED",
                "AD", 7500L, "SAR", null, null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(providerId, paymentIntentId, 7500L, "SAR");
        verify(ledgerService, never()).debitFromCommission(any(), any(), anyLong(), any());
        verifyNoInteractions(bookingParticipantProvider);
    }

    // ---- Stage 6 (ADR-0002): the order-origin branches ----

    @Test
    void creditsTheSellerAndDebitsTheCommissionForAnOrderOriginCompletion() {
        // The order settlement: the seller rides the OrderSellerPort seam
        // (the ledger never imports the orders module), the credit rides
        // the intent's own amount and currency, the SAME announced
        // commission rate books the debit.
        UUID paymentIntentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, buyerId, null, "COMPLETED",
                "ORDER", 17400L, "SAR", orderId, null, null);
        when(orderSellerPort.sellerOf(orderId)).thenReturn(sellerId);
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(sellerId, paymentIntentId, 17400L, "SAR");
        verify(ledgerService).debitFromCommission(sellerId, paymentIntentId,
                BigDecimal.valueOf(17400L)
                        .multiply(BigDecimal.valueOf(COMMISSION_RATE))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                "SAR");
        verifyNoInteractions(bookingParticipantProvider, loanOwnerPort);
    }

    @Test
    void mirrorsTheRefundDebitForAnOrderOriginIntent() {
        // The order refund mirrors its settlement credit exactly — same
        // seller, same amount, same currency.
        UUID paymentIntentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, buyerId, null, "REFUNDED",
                "ORDER", 17400L, "SAR", orderId, null, null);
        when(orderSellerPort.sellerOf(orderId)).thenReturn(sellerId);
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromRefund(sellerId, paymentIntentId, 17400L, "SAR");
        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
        verifyNoInteractions(bookingParticipantProvider, loanOwnerPort);
    }

    // ---- Stage 8 (ADR-0004): the loan-origin branches ----

    @Test
    void creditsTheOwnerAndDebitsTheCommissionForALoanOriginCompletion() {
        // The loan-fee settlement: the owner rides the LoanOwnerPort seam
        // (the order branch's twin, one port hop away — no second rate).
        UUID paymentIntentId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, borrowerId, null, "COMPLETED",
                "LOAN", 9000L, "SAR", null, loanId, null);
        when(loanOwnerPort.ownerOf(loanId)).thenReturn(ownerId);
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(ownerId, paymentIntentId, 9000L, "SAR");
        verify(ledgerService).debitFromCommission(ownerId, paymentIntentId,
                BigDecimal.valueOf(9000L)
                        .multiply(BigDecimal.valueOf(COMMISSION_RATE))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                "SAR");
        verifyNoInteractions(bookingParticipantProvider, orderSellerPort);
    }

    @Test
    void mirrorsTheRefundDebitForALoanOriginIntent() {
        // The loan-fee refund mirrors its settlement credit exactly (the
        // order branch's twin).
        UUID paymentIntentId = UUID.randomUUID();
        UUID loanId = UUID.randomUUID();
        UUID borrowerId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, borrowerId, null, "REFUNDED",
                "LOAN", 9000L, "SAR", null, loanId, null);
        when(loanOwnerPort.ownerOf(loanId)).thenReturn(ownerId);
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromRefund(ownerId, paymentIntentId, 9000L, "SAR");
        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
        verifyNoInteractions(bookingParticipantProvider, orderSellerPort);
    }

    // ---- ADR-0013 (D2): the commission freeze proofs ----

    @Test
    void postsTheFrozenCommissionRateNotTheLiveConfiguration() {
        // ADR-0013 D2's own required proof: the rate that posts is the one
        // FROZEN on the intent at creation — the listener was constructed
        // with a LIVE rate of 0.10 (a config change after placement), the
        // intent carries the frozen 0.05 the parties agreed to at creation,
        // and the posted commission is the FROZEN value. Stripe's rule:
        // application_fee_amount is fixed at charge creation, never
        // recomputed at settlement.
        UUID paymentIntentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        java.math.BigDecimal frozenRate = new java.math.BigDecimal("0.05");
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                "COMPLETED", "ORDER", 20000L, "SAR", orderId, null, frozenRate);
        when(orderSellerPort.sellerOf(orderId)).thenReturn(sellerId);
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(sellerId, paymentIntentId, 20000L, "SAR");
        verify(ledgerService).debitFromCommission(sellerId, paymentIntentId,
                BigDecimal.valueOf(20000L)
                        .multiply(frozenRate)
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                "SAR");
    }

    @Test
    void fallsBackToTheLiveRateOnlyForLegacyPreFreezeIntents() {
        // The pre-V220 rows carry no frozen rate (their creation-time rate
        // is unknowable after the fact) — the live configuration remains
        // their settlement's source, the ADR's interim rule made structural.
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        long priceCents = 5000L;
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null,
                "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
        var bookingInfo = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED",
                priceCents, "USD", Instant.now(), Instant.now());

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromCommission(providerId, paymentIntentId,
                BigDecimal.valueOf(priceCents)
                        .multiply(BigDecimal.valueOf(COMMISSION_RATE))
                        .setScale(0, RoundingMode.HALF_UP)
                        .longValue(),
                "USD");
    }

    @Test
    void mirrorsTheRefundDebitForAnAdOriginIntent() {
        // W5 (CodeRabbit r1, adopted): an ad bill's refund MIRRORS its
        // settlement credit exactly the way a booking's mirrors its own —
        // the refund surface reaches AD-origin intents as surely as
        // BOOKING ones, so skipping would leave the provider's balance
        // holding a reversed credit. The refund-<intentId> source key
        // mirrors once, structurally.
        UUID paymentIntentId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, providerId, UUID.randomUUID(), "REFUNDED",
                "AD", 7500L, "SAR", null, null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromRefund(providerId, paymentIntentId, 7500L, "SAR");
        verifyNoInteractions(bookingParticipantProvider);
    }

    // ---- The payment/ledger source audit's critical finding (PR #603):
    //      the exhaustive, fail-closed origin dispatch ----

    @Test
    void failsClosedOnAnUnknownOriginCompletion() {
        // The audit's DELIVERY example: an origin this consumer does not
        // know must NEVER fall through to the booking branch (the
        // pre-fix code would have called getBookingInfo(null)). The
        // explicit refusal: no credit, no commission, no booking lookup —
        // the publication stays recoverable for the owning implementation.
        UUID paymentIntentId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                "COMPLETED", "UNKNOWN", 6200L, "SAR", null, null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        assertThrows(IllegalStateException.class, () -> listener.onPaymentCompleted(event));

        verifyNoInteractions(bookingParticipantProvider, orderSellerPort, loanOwnerPort);
        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
        verify(ledgerService, never()).debitFromCommission(any(), any(), anyLong(), any());
        verify(ledgerService, never()).debitFromRefund(any(), any(), anyLong(), any());
    }

    @Test
    void failsClosedOnAnUnknownOriginRefund() {
        // The refund path's twin: an unknown origin debits nothing — the
        // explicit refusal, never a mirrored-booking debit.
        UUID paymentIntentId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                "REFUNDED", "UNKNOWN", 6200L, "SAR", null, null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        assertThrows(IllegalStateException.class, () -> listener.onPaymentCompleted(event));

        verifyNoInteractions(bookingParticipantProvider, orderSellerPort, loanOwnerPort);
        verify(ledgerService, never()).debitFromRefund(any(), any(), anyLong(), any());
        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong(), any());
    }

    @Test
    void deliveryCompletionIsLeftToTheDedicatedJournal() {
        UUID paymentIntentId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                "COMPLETED", "DELIVERY", 6200L, "SAR", null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verifyNoInteractions(ledgerService, bookingParticipantProvider, orderSellerPort, loanOwnerPort);
    }

    @Test
    void deliveryRefundIsLeftToTheDedicatedJournal() {
        UUID paymentIntentId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "REFUNDED");
        var intent = new PaymentIntentDetails(paymentIntentId, null, UUID.randomUUID(), null,
                "REFUNDED", "DELIVERY", 6200L, "SAR", null, null);

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verifyNoInteractions(ledgerService, bookingParticipantProvider, orderSellerPort, loanOwnerPort);
    }

}

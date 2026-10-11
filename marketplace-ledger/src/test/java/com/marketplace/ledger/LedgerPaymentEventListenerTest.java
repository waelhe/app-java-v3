package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
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
import static org.mockito.Mockito.*;

class LedgerPaymentEventListenerTest {

    private static final double COMMISSION_RATE = 0.10;

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final OrderSellerPort orderSellerPort = org.mockito.Mockito.mock(OrderSellerPort.class);

    private final LedgerPaymentEventListener listener = new LedgerPaymentEventListener(
            ledgerService, paymentIntentLookupPort, bookingParticipantProvider, orderSellerPort, COMMISSION_RATE);

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
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "REFUNDED", "BOOKING", 25000L, "SAR", null);
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
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null);
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
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null);

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
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null);
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
                "AD", 7500L, "SAR");

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(providerId, paymentIntentId, 7500L, "SAR");
        verify(ledgerService, never()).debitFromCommission(any(), any(), anyLong(), any());
        verifyNoInteractions(bookingParticipantProvider);
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
                "AD", 7500L, "SAR");

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

        listener.onPaymentCompleted(event);

        verify(ledgerService).debitFromRefund(providerId, paymentIntentId, 7500L, "SAR");
        verifyNoInteractions(bookingParticipantProvider);
    }
}

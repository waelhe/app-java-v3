package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class LedgerPaymentEventListenerTest {

    private static final double COMMISSION_RATE = 0.10;

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final PaymentIntentLookupPort paymentIntentLookupPort = mock(PaymentIntentLookupPort.class);
    private final BookingParticipantProvider bookingParticipantProvider = mock(BookingParticipantProvider.class);
    private final LedgerPaymentEventListener listener = new LedgerPaymentEventListener(
            ledgerService, paymentIntentLookupPort, bookingParticipantProvider, COMMISSION_RATE);

    @Test
    void ignoresNonCompletedEvents() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "PENDING");
        listener.onPaymentCompleted(event);
        verifyNoInteractions(paymentIntentLookupPort, bookingParticipantProvider, ledgerService);
    }

    @Test
    void creditsLedgerOnCompletedPayment() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        long priceCents = 5000L;
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), "COMPLETED");
        var bookingInfo = new BookingInfo(providerId, UUID.randomUUID(), "CONFIRMED",
                priceCents, "USD", Instant.now(), Instant.now());

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenReturn(bookingInfo);

        listener.onPaymentCompleted(event);

        verify(ledgerService).creditFromPayment(providerId, paymentIntentId, priceCents);
        verify(ledgerService).debitFromCommission(providerId, paymentIntentId, (long) (priceCents * COMMISSION_RATE));
    }

    @Test
    void logsErrorWhenBookingLookupFails() {
        UUID paymentIntentId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");
        var intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), "COMPLETED");

        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        when(bookingParticipantProvider.getBookingInfo(bookingId)).thenThrow(new RuntimeException("lookup failed"));

        listener.onPaymentCompleted(event);

        verify(ledgerService, never()).creditFromPayment(any(), any(), anyLong());
    }
}

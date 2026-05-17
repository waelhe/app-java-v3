package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationEventListenerTest {

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private NotificationEventListener listener;

    @Test
    void onBookingCreatedDelegatesToService() {
        UUID bookingId = UUID.randomUUID();
        var event = new BookingCreatedEvent(bookingId);

        listener.onBookingCreated(event);

        verify(notificationService).onBookingCreated(bookingId);
    }

    @Test
    void onPaymentStateChangedDelegatesToService() {
        UUID paymentIntentId = UUID.randomUUID();
        var event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");

        listener.onPaymentStateChanged(event);

        verify(notificationService).onPaymentStateChanged(paymentIntentId, "COMPLETED");
    }
}

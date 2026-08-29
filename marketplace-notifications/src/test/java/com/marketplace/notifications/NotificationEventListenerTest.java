package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = NotificationEventListenerTest.TestConfig.class)
class NotificationEventListenerTest {

    @Configuration
    static class TestConfig {
        @Bean
        NotificationEventListener notificationEventListener(NotificationService notificationService) {
            return new NotificationEventListener(notificationService);
        }
    }

    @MockitoBean
    NotificationService notificationService;

    @Autowired
    NotificationEventListener listener;

    @Test
    void onBookingCreated_callsNotificationService() {
        UUID bookingId = UUID.randomUUID();
        BookingCreatedEvent event = new BookingCreatedEvent(bookingId);

        listener.onBookingCreated(event);

        verify(notificationService).onBookingCreated(bookingId);
    }

    @Test
    void onBookingCreated_propagatesException() {
        UUID bookingId = UUID.randomUUID();
        BookingCreatedEvent event = new BookingCreatedEvent(bookingId);

        doThrow(new RuntimeException("Notification error"))
                .when(notificationService).onBookingCreated(bookingId);

        assertThrows(RuntimeException.class,
                () -> listener.onBookingCreated(event));
    }

    @Test
    void onPaymentStateChanged_callsNotificationService() {
        UUID intentId = UUID.randomUUID();
        PaymentStateChangedEvent event = new PaymentStateChangedEvent(intentId, "COMPLETED");

        listener.onPaymentStateChanged(event);

        verify(notificationService).onPaymentStateChanged(intentId, "COMPLETED");
    }

    @Test
    void onPaymentStateChanged_propagatesException() {
        UUID intentId = UUID.randomUUID();
        PaymentStateChangedEvent event = new PaymentStateChangedEvent(intentId, "COMPLETED");

        doThrow(new RuntimeException("Notification error"))
                .when(notificationService).onPaymentStateChanged(intentId, "COMPLETED");

        assertThrows(RuntimeException.class,
                () -> listener.onPaymentStateChanged(event));
    }

    @Test
    void onBookingCreated_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = NotificationEventListener.class.getMethod(
                "onBookingCreated", BookingCreatedEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }

    @Test
    void onPaymentStateChanged_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = NotificationEventListener.class.getMethod(
                "onPaymentStateChanged", PaymentStateChangedEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}

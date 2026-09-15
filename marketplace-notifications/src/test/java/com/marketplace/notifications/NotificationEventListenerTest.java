package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
import com.marketplace.shared.api.SavedSearchMatchedEvent;
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

    @Test
    void onListingLeadCreated_callsNotificationService() {
        UUID leadId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        ListingLeadCreatedEvent event = new ListingLeadCreatedEvent(leadId, listingId, providerId);

        listener.onListingLeadCreated(event);

        verify(notificationService).onLeadReceived(leadId, listingId, providerId);
    }

    @Test
    void onListingLeadCreated_propagatesException() {
        ListingLeadCreatedEvent event = new ListingLeadCreatedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        doThrow(new RuntimeException("Notification error"))
                .when(notificationService).onLeadReceived(any(), any(), any());

        assertThrows(RuntimeException.class,
                () -> listener.onListingLeadCreated(event));
    }

    @Test
    void onListingLeadCreated_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = NotificationEventListener.class.getMethod(
                "onListingLeadCreated", ListingLeadCreatedEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }

    @Test
    void onSavedSearchMatched_callsNotificationServiceWithTheAggregatedCount() {
        UUID userId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var event = new SavedSearchMatchedEvent(userId, listingId,
                java.util.List.of(UUID.randomUUID(), UUID.randomUUID()));

        listener.onSavedSearchMatched(event);

        verify(notificationService).onSavedSearchMatch(userId, listingId, 2);
    }

    @Test
    void onSavedSearchMatched_propagatesException() {
        var event = new SavedSearchMatchedEvent(UUID.randomUUID(), UUID.randomUUID(),
                java.util.List.of(UUID.randomUUID()));

        doThrow(new RuntimeException("Notification error"))
                .when(notificationService).onSavedSearchMatch(any(), any(), anyInt());

        assertThrows(RuntimeException.class,
                () -> listener.onSavedSearchMatched(event));
    }

    @Test
    void onSavedSearchMatched_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = NotificationEventListener.class.getMethod(
                "onSavedSearchMatched", SavedSearchMatchedEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}

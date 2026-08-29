package com.marketplace.booking;

import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
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

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BookingPaymentEventListenerTest.TestConfig.class)
class BookingPaymentEventListenerTest {

    @Configuration
    static class TestConfig {
        @Bean
        BookingPaymentEventListener bookingPaymentEventListener(
                BookingService bookingService,
                PaymentIntentLookupPort paymentIntentLookupPort) {
            return new BookingPaymentEventListener(bookingService, paymentIntentLookupPort);
        }
    }

    @MockitoBean
    BookingService bookingService;

    @MockitoBean
    PaymentIntentLookupPort paymentIntentLookupPort;

    @Autowired
    BookingPaymentEventListener listener;

    @Test
    void onPaymentCompleted_withNonCompletedState_doesNothing() {
        PaymentStateChangedEvent event = new PaymentStateChangedEvent(
                UUID.randomUUID(), "PENDING");

        listener.onPaymentCompleted(event);

        verify(bookingService, never()).autoConfirm(any());
    }

    @Test
    void onPaymentCompleted_propagatesException() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        PaymentStateChangedEvent event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");

        PaymentIntentDetails intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), "COMPLETED");
        when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));
        doThrow(new RuntimeException("Database error")).when(bookingService).autoConfirm(bookingId);

        assertThrows(RuntimeException.class,
                () -> listener.onPaymentCompleted(event));
    }

    @Test
    void onPaymentCompleted_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = BookingPaymentEventListener.class.getMethod(
                "onPaymentCompleted", PaymentStateChangedEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}

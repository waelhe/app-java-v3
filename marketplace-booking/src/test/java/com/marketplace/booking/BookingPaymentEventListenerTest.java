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

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    void onPaymentCompleted_doesNotConfirmForNonBookingOrigins() {
        for (String origin : new String[]{"AD", "ORDER", "LOAN", "DELIVERY", "UNKNOWN"}) {
            UUID paymentIntentId = UUID.randomUUID();
            PaymentIntentDetails intent = new PaymentIntentDetails(
                    paymentIntentId, null, UUID.randomUUID(), null,
                    "COMPLETED", origin, 25000L, "SAR", null, null, null);
            when(paymentIntentLookupPort.findById(paymentIntentId)).thenReturn(Optional.of(intent));

            listener.onPaymentCompleted(new PaymentStateChangedEvent(paymentIntentId, "COMPLETED"));
        }

        verifyNoInteractions(bookingService);
    }

    @Test
    void onPaymentCompleted_propagatesException() {
        UUID bookingId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        PaymentStateChangedEvent event = new PaymentStateChangedEvent(paymentIntentId, "COMPLETED");

        PaymentIntentDetails intent = new PaymentIntentDetails(paymentIntentId, bookingId, UUID.randomUUID(), null, "COMPLETED", "BOOKING", 25000L, "SAR", null, null, null);
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
package com.marketplace.payments;

import com.marketplace.shared.api.BookingCancelledEvent;
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
@ContextConfiguration(classes = BookingCancelledEventListenerTest.TestConfig.class)
class BookingCancelledEventListenerTest {

    @Configuration
    static class TestConfig {
        @Bean
        BookingCancelledEventListener bookingCancelledEventListener(PaymentsService paymentsService) {
            return new BookingCancelledEventListener(paymentsService);
        }
    }

    @MockitoBean
    PaymentsService paymentsService;

    @Autowired
    BookingCancelledEventListener listener;

    @Test
    void onBookingCancelled_callsAutoRefund() {
        UUID bookingId = UUID.randomUUID();
        BookingCancelledEvent event = new BookingCancelledEvent(bookingId);

        listener.onBookingCancelled(event);

        verify(paymentsService).autoRefundByBooking(bookingId);
    }

    @Test
    void onBookingCancelled_propagatesException() {
        UUID bookingId = UUID.randomUUID();
        BookingCancelledEvent event = new BookingCancelledEvent(bookingId);

        doThrow(new RuntimeException("Payment service error"))
                .when(paymentsService).autoRefundByBooking(bookingId);

        assertThrows(RuntimeException.class,
                () -> listener.onBookingCancelled(event));
    }

    @Test
    void onBookingCancelled_usesApplicationModuleListenerAnnotation() throws NoSuchMethodException {
        var method = BookingCancelledEventListener.class.getMethod(
                "onBookingCancelled", BookingCancelledEvent.class);
        ApplicationModuleListener ann = method.getAnnotation(ApplicationModuleListener.class);
        assertNotNull(ann);
    }
}

package com.marketplace.payments;

import com.marketplace.shared.api.BookingCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class BookingCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingCancelledEventListener.class);

    private final PaymentsService paymentsService;

    public BookingCancelledEventListener(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @ApplicationModuleListener
    public void onBookingCancelled(BookingCancelledEvent event) {
        paymentsService.autoRefundByBooking(event.bookingId());
        log.info("Auto-refund triggered for booking: {}", event.bookingId());
    }
}

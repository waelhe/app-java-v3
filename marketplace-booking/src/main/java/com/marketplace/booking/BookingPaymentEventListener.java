package com.marketplace.booking;

import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class BookingPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(BookingPaymentEventListener.class);

    private final BookingService bookingService;
    private final PaymentIntentLookupPort paymentIntentLookupPort;

    public BookingPaymentEventListener(BookingService bookingService,
                                        PaymentIntentLookupPort paymentIntentLookupPort) {
        this.bookingService = bookingService;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
    }

    @ApplicationModuleListener
    public void onPaymentCompleted(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        paymentIntentLookupPort.findById(event.paymentIntentId()).ifPresent(intent -> {
            // The payment/ledger source audit's recommendation 7 (PR #603,
            // merged): this listener owns only the BOOKING source — other
            // payment origins must never be interpreted as a booking merely
            // because this listener receives the shared payment-state event
            // (the pre-fix guard skipped only AD, so an ORDER/LOAN/unknown
            // completion reached autoConfirm(null)).
            if (!intent.isBookingOrigin()) {
                log.info("Ignoring payment completion for non-booking origin: "
                                + "paymentIntentId={}, origin={}",
                        intent.paymentIntentId(), intent.origin());
                return;
            }
            bookingService.autoConfirm(intent.bookingId());
            log.info("Booking auto-confirmed from payment: bookingId={}, paymentIntentId={}",
                    intent.bookingId(), intent.paymentIntentId());
        });
    }
}

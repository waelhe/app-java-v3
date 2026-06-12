package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerPaymentEventListener.class);

    private final LedgerService ledgerService;
    private final PaymentIntentLookupPort paymentIntentLookupPort;
    private final BookingParticipantProvider bookingParticipantProvider;

    public LedgerPaymentEventListener(LedgerService ledgerService,
                                       PaymentIntentLookupPort paymentIntentLookupPort,
                                       BookingParticipantProvider bookingParticipantProvider) {
        this.ledgerService = ledgerService;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.bookingParticipantProvider = bookingParticipantProvider;
    }

    @ApplicationModuleListener
    public void onPaymentCompleted(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        paymentIntentLookupPort.findById(event.paymentIntentId()).ifPresent(intent -> {
            try {
                BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
                ledgerService.creditFromPayment(bookingInfo.providerId(), intent.paymentIntentId(), bookingInfo.priceCents());
                log.info("Ledger credited from payment: intentId={}, providerId={}, amount={}",
                        intent.paymentIntentId(), bookingInfo.providerId(), bookingInfo.priceCents());
            } catch (Exception e) {
                log.error("Failed to credit ledger from payment: intentId={}", intent.paymentIntentId(), e);
            }
        });
    }
}

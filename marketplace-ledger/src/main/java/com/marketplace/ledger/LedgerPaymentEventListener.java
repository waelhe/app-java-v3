package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerPaymentEventListener.class);

    private final LedgerService ledgerService;
    private final PaymentIntentLookupPort paymentIntentLookupPort;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final double commissionRate;

    public LedgerPaymentEventListener(LedgerService ledgerService,
                                       PaymentIntentLookupPort paymentIntentLookupPort,
                                       BookingParticipantProvider bookingParticipantProvider,
                                       @Value("${app.commission.rate:0.10}") double commissionRate) {
        this.ledgerService = ledgerService;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.commissionRate = commissionRate;
    }

    @ApplicationModuleListener
    public void onPaymentCompleted(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        paymentIntentLookupPort.findById(event.paymentIntentId()).ifPresent(intent -> {
            try {
                BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
                long priceCents = bookingInfo.priceCents();
                ledgerService.creditFromPayment(bookingInfo.providerId(), intent.paymentIntentId(), priceCents);
                long commissionCents = (long) (priceCents * commissionRate);
                ledgerService.debitFromCommission(bookingInfo.providerId(), intent.paymentIntentId(), commissionCents);
                log.info("Ledger processed: credited {} to provider {}, debited {} as commission",
                        priceCents, bookingInfo.providerId(), commissionCents);
            } catch (Exception e) {
                log.error("Failed to credit ledger from payment: intentId={}", intent.paymentIntentId(), e);
            }
        });
    }
}

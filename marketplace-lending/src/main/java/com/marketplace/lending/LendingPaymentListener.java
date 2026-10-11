package com.marketplace.lending;

import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 8 (ADR-0004): the fee settlement's machine edge — the payment's
 * COMPLETED event marks the loan paid (the OrdersPaymentListener twin;
 * idempotent by the state guard — a redelivered event for an already-paid
 * loan is an honest no-op).
 */
@Component
public class LendingPaymentListener {

    private static final Logger log = LoggerFactory.getLogger(LendingPaymentListener.class);

    private final LendingService lendingService;

    public LendingPaymentListener(LendingService lendingService) {
        this.lendingService = lendingService;
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        lendingService.markPaidFromPayment(event.paymentIntentId());
        log.info("Payment {} settled — the loan's paid gate executed", event.paymentIntentId());
    }
}

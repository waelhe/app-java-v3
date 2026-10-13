package com.marketplace.orders;

import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 6 (ADR-0002): the settlement's machine edge — the payment's
 * COMPLETED event (published by the payments settlement service inside its
 * own transaction, consumed AFTER_COMMIT in the listener's independent
 * unit — the Modulith events contract) auto-confirms the PLACED order.
 *
 * <p>The booking module's auto-confirm twin verbatim: the machine's own
 * state guard is the idempotency — a redelivered event for an
 * already-CONFIRMED (or moved-past) order is an honest no-op, never a 409
 * the provider's retry would trap on.
 */
@Component
public class OrdersPaymentListener {

    private static final Logger log = LoggerFactory.getLogger(OrdersPaymentListener.class);

    private final OrdersService ordersService;

    public OrdersPaymentListener(OrdersService ordersService) {
        this.ordersService = ordersService;
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        ordersService.confirmFromPayment(event.paymentIntentId());
        log.info("Payment {} settled — the order auto-confirm path executed", event.paymentIntentId());
    }
}

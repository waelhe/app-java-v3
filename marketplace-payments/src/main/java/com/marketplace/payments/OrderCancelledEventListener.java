package com.marketplace.payments;

import com.marketplace.shared.api.OrderCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 6 (ADR-0002): the order cancellation's money edge — the
 * {@code BookingCancelledEventListener} twin verbatim. The order machine's
 * cancel transition publishes; the EXISTING engine settles here (cancel
 * the unpaid intent, fail the in-flight one, fully refund the collected
 * one) in the listener's own AFTER_COMMIT unit — a consumer failure never
 * rolls the business state back.
 */
@Component
public class OrderCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledEventListener.class);

    private final PaymentsService paymentsService;

    public OrderCancelledEventListener(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @ApplicationModuleListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        paymentsService.autoRefundByOrder(event.orderId());
        log.info("Auto-refund triggered for order: {}", event.orderId());
    }
}

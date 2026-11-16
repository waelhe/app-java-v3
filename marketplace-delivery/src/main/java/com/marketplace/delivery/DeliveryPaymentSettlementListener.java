package com.marketplace.delivery;

import com.marketplace.shared.api.OrderFulfillmentSelection;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Agent findings 2026-10-26T13:11 export, row 41 (CWE-841) — the
 * settlement edge of the checkout delivery leg. The confirmation edge
 * ({@code OrderFulfillmentListener}) now requires VERIFIED payment
 * before creating a dispatch-ready leg, so a seller-first confirmed
 * order whose payment has not settled yet carries no leg; THIS listener
 * is the second half that keeps the buyer's paid order whole: when the
 * order's frozen payable settles (the payment's COMPLETED event, the
 * same machine edge the orders module's own auto-confirm listens to),
 * the order's frozen fulfillment selection resolves and the leg is
 * created from it — the same FROZEN facts, the same idempotent service
 * ({@code createOrderLegFromFulfillment}'s existing-active-leg check
 * answers the already-created leg on a redelivered event, never a
 * second one).
 *
 * <p>The absent answer is the honest no-op for every other shape (the
 * {@code OrderDeliveryPort#findOrderFulfillmentByPaymentIntent}
 * contract): a non-order intent, an order still awaiting confirmation
 * (the confirmation edge still owes its leg once payment verifies), and
 * an order that moved past CONFIRMED — their legs are other edges'
 * concerns. FAILED and every non-COMPLETED state never reach here.</p>
 */
@Component
public class DeliveryPaymentSettlementListener {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPaymentSettlementListener.class);

    private final DeliveryService deliveryService;
    private final com.marketplace.shared.api.OrderDeliveryPort orders;

    public DeliveryPaymentSettlementListener(DeliveryService deliveryService,
                                             com.marketplace.shared.api.OrderDeliveryPort orders) {
        this.deliveryService = deliveryService;
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if (!"COMPLETED".equals(event.state())) {
            return;
        }
        orders.findOrderFulfillmentByPaymentIntent(event.paymentIntentId())
                .filter(OrderFulfillmentSelection::platformDelivery)
                .ifPresent(selection -> {
                    // The payable is settled BY CONSTRUCTION here — this
                    // edge only fires on the payment's COMPLETED machine
                    // event; no second payment probe is owed.
                    deliveryService.createOrderLegFromFulfillment(selection);
                    log.info("Payment {} settled — the checkout platform-delivery leg created for "
                            + "the seller-confirmed order {}", event.paymentIntentId(), selection.orderId());
                });
    }
}

package com.marketplace.delivery;

import com.marketplace.shared.api.OrderConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The V224 confirmation edge (the architecture §3.2 Mode B step 4: «after
 * the required commercial state — confirmed payment and/or seller
 * acceptance — create one or more shipment/delivery jobs»): the order's
 * CONFIRMED event creates the checkout-selected platform delivery's leg
 * from the FROZEN selection — no re-quote for the buyer, no second
 * charge, one leg per seller order (the multi-leg splits are the
 * operator's {@code addLegForOrder} command).
 *
 * <p>Idempotent by the service's existing-active-leg check: a redelivered
 * event finds its leg and returns it (the Modulith events contract's
 * recoverable consumer). Non-platform-delivery orders (PICKUP or the
 * legacy method-less shape) are an honest no-op — their fulfillment is
 * not the delivery domain's to create.
 */
@Component
public class OrderFulfillmentListener {

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentListener.class);

    private final DeliveryService deliveryService;
    private final com.marketplace.shared.api.OrderDeliveryPort orders;

    public OrderFulfillmentListener(DeliveryService deliveryService,
                                    com.marketplace.shared.api.OrderDeliveryPort orders) {
        this.deliveryService = deliveryService;
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        com.marketplace.shared.api.OrderFulfillmentSelection selection;
        try {
            // Part-2 review round 2: the PRIMARY leg creation serializes with
            // the fulfillment gate on the order row's write lock — the same
            // serialized surface the split command rides.
            selection = orders.requireOrderFulfillmentForUpdate(event.orderId(), event.consumerId());
        } catch (com.marketplace.shared.api.ConflictException
                 | com.marketplace.shared.api.ResourceNotFoundException ex) {
            // The order moved past CONFIRMED (a redelivered event) or the
            // buyer's order vanished — the honest no-op, never a trap for
            // the framework's retry.
            log.debug("Fulfillment leg creation skipped for order {} — {}",
                    event.orderId(), ex.getMessage());
            return;
        }
        if (!selection.platformDelivery()) {
            log.debug("Order {} confirmed without a checkout delivery selection — no leg to create",
                    event.orderId());
            return;
        }
        deliveryService.createOrderLegFromFulfillment(selection);
        log.info("Order {} confirmed — the checkout platform-delivery leg created from the frozen selection",
                event.orderId());
    }
}

package com.marketplace.orders;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import com.marketplace.shared.api.DeliveryLegTerminatedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The order module owns stock and order transitions. A committed delivery fact
 * crosses the shared event contract; this listener commits the order only
 * after physical proof of delivery, and removes failed shipment links so the
 * buyer may request a fresh quote.
 *
 * <p>CodeRabbit agent findings, row 16 — the terminal leg carries its own
 * fact ({@code DeliveryLegTerminatedEvent}): a leg that ended WITHOUT
 * physical delivery (cancelled or failed) can still complete the aggregate —
 * when it was the LAST active leg and a sibling was already delivered, the
 * order fulfills through the same verified edge. The reconciliation lives in
 * {@code OrdersService.reconcileDeliveryTermination} (every non-completing
 * aggregate state is an honest no-op there, so the event's redelivery never
 * surfaces as an error).
 */
@Component
public class OrdersDeliveryEventListener {

    private final OrdersService orders;

    public OrdersDeliveryEventListener(OrdersService orders) {
        this.orders = orders;
    }

    @ApplicationModuleListener
    public void onDeliveryCompleted(DeliveryCompletedEvent event) {
        if (event.orderId() != null) {
            orders.fulfillFromDelivery(event.orderId(), event.deliveryRequestId());
        }
    }

    @ApplicationModuleListener
    public void onDeliveryLegTerminated(DeliveryLegTerminatedEvent event) {
        if (event.orderId() != null) {
            orders.reconcileDeliveryTermination(event.orderId(), event.deliveryRequestId());
        }
    }

}

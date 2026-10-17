package com.marketplace.orders;

import com.marketplace.shared.api.DeliveryCompletedEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The order module owns stock and order transitions. A committed delivery fact
 * crosses the shared event contract; this listener commits the order only
 * after physical proof of delivery, and removes failed shipment links so the
 * buyer may request a fresh quote.
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

}

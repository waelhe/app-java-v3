package com.marketplace.orders;

import com.marketplace.orders.spi.OrdersContentPurgeAdapter;
import com.marketplace.shared.api.OrderCancelledEvent;
import com.marketplace.shared.api.OrderFulfilledEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * The terminal-completion leg of the orders content purge (the
 * 2026-11-23 #808 review's third finding, closed at the root): the
 * address purge holds back in-flight deliveries ({@code PLACED} /
 * {@code CONFIRMED}) because the frozen destination is the delivery's
 * own input — the adapter's own comment promises those rows become
 * erasable "after the order reaches FULFILLED or CANCELLED", but no
 * mechanism ever fired that later run (the port only ran through the
 * admin's UserService path, so an order completing AFTER its buyer's
 * erasure kept the address until some operator re-ran the purge by
 * hand).
 *
 * <p>This listener IS that mechanism: the module's own terminal events —
 * {@link OrderFulfilledEvent} and {@link OrderCancelledEvent}, both
 * published inside the transitioning transaction — drive
 * {@link OrdersContentPurgeAdapter#purgeTerminalOrderAddress(UUID)} for
 * THAT order the moment the transition commits. The
 * {@code @ApplicationModuleListener} contract (the house pattern — the
 * L28 media-thumbnail precedent of a same-module AFTER_COMMIT listener):
 * the purge runs in its own {@code REQUIRES_NEW} transaction after the
 * order's commit, so it never joins — and never rolls back — the
 * fulfillment/cancellation it reacts to; a purge failure marks the
 * publication FAILED and the registry's standing resubmission retries it
 * (the at-least-once law), never touching the order's own transition.</p>
 *
 * <p>Idempotence is the adapter's own: the marker filter makes event
 * redelivery a zero-row no-op, and the admin-path statements carry the
 * same filter — the two legs can never double-purge or race past each
 * other (both target the same rows with the same
 * {@code IS NOT NULL AND <> marker} guard).</p>
 */
@Component
public class OrdersTerminalPurgeListener {

    private final OrdersContentPurgeAdapter purgeAdapter;

    public OrdersTerminalPurgeListener(OrdersContentPurgeAdapter purgeAdapter) {
        this.purgeAdapter = purgeAdapter;
    }

    @ApplicationModuleListener
    public void onOrderFulfilled(OrderFulfilledEvent event) {
        // The payload's consumerId rides the event for the notification
        // consumers; the targeted purge keys on the ORDER alone — the
        // module's own row, no cross-module identity needed.
        purgeAdapter.purgeTerminalOrderAddress(event.orderId());
    }

    @ApplicationModuleListener
    public void onOrderCancelled(OrderCancelledEvent event) {
        purgeAdapter.purgeTerminalOrderAddress(event.orderId());
    }
}

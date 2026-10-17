package com.marketplace.shared.api;

import java.util.UUID;

/**
 * The platform-wide payments-and-delivery architecture §3.2 Mode B: the
 * checkout fulfillment seam — the orders module resolves the delivery
 * options for a cart BEFORE placement (the fee is a synchronous
 * prerequisite of the commercial agreement, exactly like stock), freezes
 * the selection on the order, and reads an order's remaining active legs
 * at the fulfillment/cancellation gates. The delivery module implements
 * this through its own adapter bean — deliberately NOT through
 * {@code DeliveryService}, so the constructor graph never cycles
 * (OrdersService → this port → the delivery repos; DeliveryService keeps
 * its own OrderDeliveryPort dependency in the opposite direction).
 */
public interface CheckoutDeliveryPort {

    /**
     * Resolves the platform-delivery fare for one seller's group at a
     * destination — the checkout preview and the placement's synchronous
     * prerequisite read. An unserviceable route (or an unconfigured
     * delivery) answers the HONEST non-serviceable option — never an
     * exception: the checkout must show «no platform delivery for this
     * seller», the buyer picks pickup or removes the lines.
     */
    DeliveryFareOption fareFor(UUID sellerId, double dropoffLatitude, double dropoffLongitude);

    /**
     * The order's remaining active delivery legs — the count the
     * fulfillment and cancellation gates read: an order with a
     * checkout-selected platform delivery fulfills only when EVERY leg
     * reaches a terminal state, and cannot be cancelled while any leg is
     * in flight (the multi-leg law).
     */
    long remainingActiveLegs(UUID orderId);

    /**
     * One seller group's delivery fare — the facts the checkout shows and
     * the placement freezes. {@code refusal} carries the honest
     * non-serviceability reason (null when serviceable).
     */
    record DeliveryFareOption(boolean serviceable, long feeMinor, long etaSeconds,
                              String currency, String refusal) {

        public static DeliveryFareOption unserviceable(String refusal) {
            return new DeliveryFareOption(false, 0, 0, null, refusal);
        }
    }
}

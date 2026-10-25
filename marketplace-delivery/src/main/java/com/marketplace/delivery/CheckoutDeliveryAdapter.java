package com.marketplace.delivery;

import com.marketplace.shared.api.CheckoutDeliveryPort;
import com.marketplace.shared.api.ProviderFulfillmentPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The V224 checkout seam's implementation (the architecture §3.2 Mode B):
 * the orders module's placement resolves each seller group's
 * platform-delivery fare and reads an order's remaining active legs
 * through THIS bean — deliberately NOT through {@link DeliveryService},
 * so the constructor graph never cycles (OrdersService → this adapter →
 * the delivery repos/routing; DeliveryService keeps its own
 * {@code OrderDeliveryPort} dependency in the opposite direction —
 * two acyclic halves around the one seam).
 *
 * <p><b>The honest non-serviceable option:</b> {@link #fareFor} answers
 * {@code serviceable=false} with the refusal reason — never an exception.
 * The checkout must SHOW «no platform delivery for this seller», the
 * buyer removes the lines or picks pickup; a thrown 409 here would make
 * the preview endpoint lie about the cart's deliverability.
 */
@Component
@Transactional(readOnly = true)
public class CheckoutDeliveryAdapter implements CheckoutDeliveryPort {

    private final DeliveryProperties properties;
    private final DeliveryRouteProvider routes;
    private final ProviderFulfillmentPort fulfillmentLocations;
    private final DeliveryRequestRepository requests;

    public CheckoutDeliveryAdapter(DeliveryProperties properties,
                                   DeliveryRouteProvider routes,
                                   ProviderFulfillmentPort fulfillmentLocations,
                                   DeliveryRequestRepository requests) {
        this.properties = properties;
        this.routes = routes;
        this.fulfillmentLocations = fulfillmentLocations;
        this.requests = requests;
    }

    @Override
    public DeliveryFareOption fareFor(UUID sellerId, double dropoffLatitude, double dropoffLongitude) {
        // The parity law (the review's Major finding, root-caused): the
        // checkout's serviceability answer must agree with what leg
        // creation will accept AFTER the buyer pays — a configuration
        // leg creation refuses (createOrderLegFromFulfillment's own
        // ensureConfigured margin rules) must be refused HERE, before the
        // money moves, or every OrderConfirmedEvent retry fails on a paid
        // order with no delivery leg ever created.
        if (!properties.enabled()
                || properties.baseFeeMinor() <= 0 || properties.perKilometerMinor() < 0
                || properties.maxDistanceMeters() <= 0
                || properties.currency() == null
                || !properties.currency().trim().matches("[A-Za-z]{3}")
                || properties.courierBaseCompensationMinor() <= 0
                || properties.courierCompensationPerKilometerMinor() < 0) {
            return DeliveryFareOption.unserviceable("the delivery service is not enabled");
        }
        var pickup = fulfillmentLocations.findVerifiedLocation(sellerId).orElse(null);
        if (pickup == null) {
            return DeliveryFareOption.unserviceable(
                    "the seller has no verified pickup location");
        }
        DeliveryRouteMetrics route;
        try {
            route = routes.route(pickup.latitude(), pickup.longitude(),
                    dropoffLatitude, dropoffLongitude);
        } catch (RuntimeException ex) {
            return DeliveryFareOption.unserviceable("the routing provider is unavailable");
        }
        if (route.distanceMeters() > properties.maxDistanceMeters()) {
            return DeliveryFareOption.unserviceable(
                    "the route is outside the configured delivery area");
        }
        long km = Math.max(1, (route.distanceMeters() + 999L) / 1000L);
        long fee;
        long compensation;
        try {
            fee = Math.addExact(properties.baseFeeMinor(),
                    Math.multiplyExact(properties.perKilometerMinor(), km));
            compensation = Math.addExact(properties.courierBaseCompensationMinor(),
                    Math.multiplyExact(properties.courierCompensationPerKilometerMinor(), km));
        } catch (ArithmeticException ex) {
            return DeliveryFareOption.unserviceable("the fare policy overflows this route");
        }
        if (compensation <= 0 || compensation >= fee) {
            // the same margin rule leg creation enforces: a fare the
            // courier's compensation consumes leaves the platform no
            // margin — unserviceable at the checkout, never a paid order
            // that cannot create its delivery legs
            return DeliveryFareOption.unserviceable(
                    "the fare policy does not cover courier compensation on this route");
        }
        return new DeliveryFareOption(true, fee, route.durationSeconds(),
                properties.currency().trim().toUpperCase(java.util.Locale.ROOT), null);
    }

    @Override
    public long remainingActiveLegs(UUID orderId) {
        return requests.countActiveByOrderId(orderId);
    }

    @Override
    public long deliveredLegs(UUID orderId) {
        return requests.countDeliveredByOrderId(orderId);
    }
}

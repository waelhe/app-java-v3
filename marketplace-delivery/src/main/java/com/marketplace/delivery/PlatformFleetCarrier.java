package com.marketplace.delivery;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * The platform's own fleet — the ADR-0013 D4 launch carrier (the mode the
 * in-flight #604 slice already implements, recorded retroactively by the
 * ADR). The {@link CarrierChannel} adapter for the existing behavior: the
 * configured OSRM-compatible routing provider resolves the route and the
 * configured service area bounds it — the same facts the quote path
 * measures, exposed through the provider-neutral boundary so external
 * carriers arrive as NEW adapters, never as edits to the core contracts.
 */
@Component
public class PlatformFleetCarrier implements CarrierChannel {

    public static final String CODE = "PLATFORM";

    private final DeliveryRouteProvider routes;
    private final DeliveryProperties properties;

    public PlatformFleetCarrier(DeliveryRouteProvider routes, DeliveryProperties properties) {
        this.routes = routes;
        this.properties = properties;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public Set<CarrierCapability> capabilities() {
        return EnumSet.allOf(CarrierCapability.class);
    }

    @Override
    public boolean serviceable(double pickupLatitude, double pickupLongitude,
                               double dropoffLatitude, double dropoffLongitude) {
        if (!properties.enabled()) {
            return false;
        }
        try {
            DeliveryRouteMetrics route = routes.route(pickupLatitude, pickupLongitude,
                    dropoffLatitude, dropoffLongitude);
            return route.distanceMeters() <= properties.maxDistanceMeters();
        } catch (RuntimeException ex) {
            return false; // the routing provider's outage is an honest non-serviceable
        }
    }
}

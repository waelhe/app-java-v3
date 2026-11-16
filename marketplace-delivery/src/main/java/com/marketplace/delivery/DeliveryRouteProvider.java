package com.marketplace.delivery;

/** Port for a configured road-routing engine. No straight-line fallback is permitted. */
public interface DeliveryRouteProvider {
    DeliveryRouteMetrics route(double fromLatitude, double fromLongitude,
                               double toLatitude, double toLongitude);
}

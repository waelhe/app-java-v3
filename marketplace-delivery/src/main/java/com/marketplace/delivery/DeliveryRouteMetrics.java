package com.marketplace.delivery;

/** Road distance and duration returned by the configured routing engine. */
public record DeliveryRouteMetrics(long distanceMeters, long durationSeconds) {
    public DeliveryRouteMetrics {
        if (distanceMeters <= 0 || durationSeconds <= 0) {
            throw new IllegalArgumentException("Route distance and duration must be positive");
        }
    }
}

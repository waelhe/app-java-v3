package com.marketplace.booking;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

public final class BookingSpecifications {

    private BookingSpecifications() {}

    public static Specification<Booking> hasConsumerId(UUID consumerId) {
        return (root, query, cb) -> cb.equal(root.get("consumerId"), consumerId);
    }

    public static Specification<Booking> hasProviderId(UUID providerId) {
        return (root, query, cb) -> cb.equal(root.get("providerId"), providerId);
    }

    public static Specification<Booking> hasListingId(UUID listingId) {
        return (root, query, cb) -> cb.equal(root.get("listingId"), listingId);
    }

    public static Specification<Booking> hasStatus(BookingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Booking> createdAfter(Instant after) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), after);
    }

    public static Specification<Booking> createdBefore(Instant before) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), before);
    }

}

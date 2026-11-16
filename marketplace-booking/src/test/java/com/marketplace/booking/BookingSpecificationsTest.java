package com.marketplace.booking;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class BookingSpecificationsTest {

    @Test
    void hasConsumerId_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.hasConsumerId(UUID.randomUUID());
        assertNotNull(spec);
    }

    @Test
    void hasProviderId_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.hasProviderId(UUID.randomUUID());
        assertNotNull(spec);
    }

    @Test
    void hasListingId_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.hasListingId(UUID.randomUUID());
        assertNotNull(spec);
    }

    @Test
    void hasStatus_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.hasStatus(BookingStatus.PENDING);
        assertNotNull(spec);
    }

    @Test
    void createdAfter_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.createdAfter(Instant.now());
        assertNotNull(spec);
    }

    @Test
    void createdBefore_returnsSpec() {
        Specification<Booking> spec = BookingSpecifications.createdBefore(Instant.now());
        assertNotNull(spec);
    }
}

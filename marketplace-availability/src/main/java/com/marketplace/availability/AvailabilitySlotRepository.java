package com.marketplace.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlot, UUID> {
    List<AvailabilitySlot> findByProviderIdAndStartsAtGreaterThanEqualAndEndsAtLessThanEqual(UUID providerId, Instant from, Instant to);
    boolean existsByProviderIdAndBookedFalseAndStartsAtLessThanAndEndsAtGreaterThan(UUID providerId, Instant endsAt, Instant startsAt);
}

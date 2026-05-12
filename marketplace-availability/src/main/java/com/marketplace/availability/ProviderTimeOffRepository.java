package com.marketplace.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ProviderTimeOffRepository extends JpaRepository<ProviderTimeOff, UUID> {
    boolean existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(UUID providerId, Instant endsAt, Instant startsAt);
}

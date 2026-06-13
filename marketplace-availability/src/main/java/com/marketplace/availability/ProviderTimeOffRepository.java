package com.marketplace.availability;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface ProviderTimeOffRepository extends JpaRepository<ProviderTimeOff, UUID>, RevisionRepository<ProviderTimeOff, UUID, Integer> {
    boolean existsByProviderIdAndStartsAtLessThanAndEndsAtGreaterThan(UUID providerId, Instant endsAt, Instant startsAt);
}

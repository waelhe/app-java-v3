package com.marketplace.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProviderTimeOffRepository extends JpaRepository<ProviderTimeOff, UUID> {
    List<ProviderTimeOff> findByProviderId(UUID providerId);

    @Query("select t from ProviderTimeOff t where t.providerId = :providerId and t.startsAt < :endsAt and t.endsAt > :startsAt")
    List<ProviderTimeOff> findOverlapping(@Param("providerId") UUID providerId, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}

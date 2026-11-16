package com.marketplace.availability;

import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

public interface ProviderAvailabilityRuleRepository extends JpaRepository<ProviderAvailabilityRule, UUID>, RevisionRepository<ProviderAvailabilityRule, UUID, Integer> {
    List<ProviderAvailabilityRule> findByDayOfWeek(DayOfWeek dayOfWeek);
}

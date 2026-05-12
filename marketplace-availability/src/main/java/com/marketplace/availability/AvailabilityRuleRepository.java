package com.marketplace.availability;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AvailabilityRuleRepository extends JpaRepository<ProviderAvailabilityRule, UUID> {
    List<ProviderAvailabilityRule> findByProviderId(UUID providerId);
}

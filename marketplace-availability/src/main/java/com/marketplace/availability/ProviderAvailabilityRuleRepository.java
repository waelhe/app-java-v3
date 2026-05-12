package com.marketplace.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProviderAvailabilityRuleRepository extends JpaRepository<ProviderAvailabilityRule, UUID> {
}

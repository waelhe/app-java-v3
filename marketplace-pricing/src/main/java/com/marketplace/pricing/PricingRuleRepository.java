package com.marketplace.pricing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PricingRuleRepository extends JpaRepository<PricingRule, UUID> {

    Optional<PricingRule> findByCategoryAndActiveTrue(String category);

    Optional<PricingRule> findFirstByActiveTrueOrderByCreatedAtDesc();
}
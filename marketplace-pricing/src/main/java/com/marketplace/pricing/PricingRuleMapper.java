package com.marketplace.pricing;

import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PricingRuleMapper {

    PricingRuleResponse toResponse(PricingRule rule);
}

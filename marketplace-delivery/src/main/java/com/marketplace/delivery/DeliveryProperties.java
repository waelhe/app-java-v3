package com.marketplace.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runtime policy for direct delivery. The feature remains disabled unless a
 * road-routing endpoint and a complete fare/compensation policy are configured.
 *
 * <p>Single canonical constructor only — the official constructor-binding
 * rule (Spring Boot reference, Features › Externalized Configuration ›
 * Constructor binding): a {@code @ConfigurationProperties} type with exactly
 * one constructor binds automatically; a second constructor would force
 * JavaBean binding, which a record cannot satisfy (measured CI failure:
 * "No default constructor found"). The manual {@code @ConstructorBinding}
 * disambiguation and the test-only convenience constructor were both removed:
 * tests ride the canonical constructor, so binding stays fully automatic.
 */
@ConfigurationProperties(prefix = "marketplace.delivery")
public record DeliveryProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("SAR") String currency,
        @DefaultValue("0") long baseFeeMinor,
        @DefaultValue("0") long perKilometerMinor,
        @DefaultValue("0") int maxDistanceMeters,
        @DefaultValue("900") long quoteTtlSeconds,
        @DefaultValue("") String routingBaseUrl,
        @DefaultValue("0") long courierBaseCompensationMinor,
        @DefaultValue("0") long courierCompensationPerKilometerMinor,
        @DefaultValue("120") long courierLocationMaxAgeSeconds,
        @DefaultValue("25") int dispatchBatchSize,
        /** The V229 at-risk multiplier: a leg is at risk when its creation + route duration × this multiplier passed. */
        @DefaultValue("3") long atRiskDurationMultiplier) { }

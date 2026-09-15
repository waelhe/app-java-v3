package com.marketplace.search;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * L35 (realestate systems plan §5 — saved searches, CodeRabbit round-1
 * adoption): the search module's type-safe configuration (constructor
 * binding, primed with an empty {@link DefaultValue} section per the house
 * binding rule — AGENTS.md; the MessagingProperties pattern).
 *
 * <p>The saved-searches section carries the availability bound: a
 * conservative per-user cap on the STORED set (the rate limiter bounds
 * the write frequency; this bounds the set the matcher scans for every
 * listing activation — an unbounded per-user set is a self-inflicted
 * scan amplifier).
 *
 * <p><b>Fail-fast binding (CodeRabbit round-2 adoption):</b> the cap is
 * {@code @Min(1)} — a non-positive environment value would otherwise flip
 * every otherwise-valid create into a 409 at the quota check before any
 * save (the "cap satisfied by zero" trap). The official validation recipe
 * applies: «Spring Boot attempts to validate @ConfigurationProperties
 * classes whenever they are annotated with Spring's @Validated annotation
 * … To cascade validation to nested properties the associated field must
 * be annotated with @Valid» (Spring Boot reference — Type-safe
 * Configuration Properties — Validation). The constraint rides the module's
 * existing {@code spring-boot-starter-validation} — zero new dependency
 * decisions.
 */
@Validated
@ConfigurationProperties(prefix = "marketplace.search")
public record SearchProperties(
        @Valid @DefaultValue SavedSearches savedSearches
) {

    public record SavedSearches(
            /**
             * The per-user cap on stored saved searches — the create path
             * answers 409 at the cap. Calibratable through the environment
             * ({@code MARKETPLACE_SEARCH_SAVEDSEARCHES_MAX_PER_USER});
             * the conservative default follows the house calibration gates
             * (G-R6-style: tune with real traffic). {@code @Min(1)}: a
             * non-positive value fails STARTUP at binding time (the
             * misconfigured deploy never comes up serving 409s).
             */
            @Min(1) @DefaultValue("20") int maxPerUser
    ) {}
}

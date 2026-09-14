package com.marketplace.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
 */
@ConfigurationProperties(prefix = "marketplace.search")
public record SearchProperties(
        @DefaultValue SavedSearches savedSearches
) {

    public record SavedSearches(
            /**
             * The per-user cap on stored saved searches — the create path
             * answers 409 at the cap. Calibratable through the environment
             * ({@code MARKETPLACE_SEARCH_SAVEDSEARCHES_MAX_PER_USER});
             * the conservative default follows the house calibration gates
             * (G-R6-style: tune with real traffic).
             */
            @DefaultValue("20") int maxPerUser
    ) {}
}

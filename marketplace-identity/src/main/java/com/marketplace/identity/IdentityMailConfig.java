package com.marketplace.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link IdentityMailProperties} — the CatalogConfig/MediaConfig
 * pattern for module-owned configuration: the module registers its own
 * properties record through its own {@code @Configuration} class, so the
 * application class never references a module-internal type (the Modulith
 * A-02 gate's own rule — the root module touches exposed channels only;
 * the direct {@code @EnableConfigurationProperties} listing on the app
 * class was the violation the gate measured in this unit's first local
 * run, fixed by moving to the house shape).
 */
@Configuration
@EnableConfigurationProperties(IdentityMailProperties.class)
class IdentityMailConfig {
}

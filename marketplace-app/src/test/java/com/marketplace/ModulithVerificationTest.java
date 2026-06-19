package com.marketplace;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies Spring Modulith module boundaries and generates PlantUML documentation.
 *
 * <p>The prior {@code assumeTrue(Runtime.version().feature() < 26)} was stale --
 * ArchUnit 1.4.2 (pulled by Spring Modulith 2.1.0) supports Java 25 (class file
 * major 69). The {@code assumeTrue} caused the test to be silently skipped on
 * JDK 26+ but also on JDK 25 when the feature number was misinterpreted.
 *
 * <p>Reference: Spring Modulith Reference -- Event Publication Registry:
 * "FAILED - The listener threw an exception."
 * https://docs.spring.io/spring-modulith/reference/events.html
 */
class ModulithVerificationTest {

    @Test
    void verifyModulesAndWriteDocs() {
        var modules = ApplicationModules.of(MarketplaceApplication.class);
        modules.verify();
        new Documenter(modules).writeDocumentation();
    }
}

package com.marketplace.discovery;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Wave D-1 (plan #536 §1.4 / JT-20): the module-boundary gate — the house
 * {@code ModulithVerificationTest}'s own machinery scoped to THIS module's
 * classpath while the app wiring rides its CR (the institutions twin's
 * own discipline): the discovery module crosses no sibling boundary —
 * every data access rides the shared named interfaces (shared-api ports,
 * shared-security caller seam, shared-jpa machinery).
 */
class DiscoveryModuleVerificationTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of("com.marketplace").verify();
    }
}

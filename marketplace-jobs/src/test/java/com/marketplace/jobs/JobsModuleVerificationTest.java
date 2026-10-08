package com.marketplace.jobs;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * B-12 (compliance plan C.2): the module-boundary gate — the house
 * {@code ModulithVerificationTest}'s own machinery
 * ({@code ApplicationModules.verify()}, Modulith {@code verification.html}),
 * scoped to THIS module's classpath while the app wiring rides its CR
 * (the root aggregator row + the app dependency row): the base package
 * scan resolves {@code jobs} (this module) and {@code shared} (the named
 * interfaces shared-api / shared-security / shared-jpa that
 * {@code allowedDependencies} declares) exactly as the app reactor will
 * once the CR lands — the same rule set, no second standard.
 */
class JobsModuleVerificationTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of("com.marketplace").verify();
    }
}

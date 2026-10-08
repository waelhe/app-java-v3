package com.marketplace.console;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * B-15 (compliance plan C.5): the module-boundary gate — the house
 * {@code ModulithVerificationTest}'s own machinery scoped to THIS
 * module's classpath while the app wiring rides its CR (the jobs,
 * institutions, and knowledge twins' own discipline).
 */
class ConsoleModuleVerificationTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of("com.marketplace").verify();
    }
}

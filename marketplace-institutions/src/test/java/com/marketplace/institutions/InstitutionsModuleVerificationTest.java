package com.marketplace.institutions;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * B-13 (compliance plan C.3): the module-boundary gate — the house
 * {@code ModulithVerificationTest}'s own machinery scoped to THIS
 * module's classpath while the app wiring rides its CR (the same rule
 * set the app reactor will enforce once the CR lands — the jobs twin's
 * own discipline).
 */
class InstitutionsModuleVerificationTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of("com.marketplace").verify();
    }
}

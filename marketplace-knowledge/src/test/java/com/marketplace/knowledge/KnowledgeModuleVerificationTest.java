package com.marketplace.knowledge;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * B-14 (compliance plan C.4): the module-boundary gate — the house
 * {@code ModulithVerificationTest}'s own machinery scoped to THIS
 * module's classpath while the app wiring rides its CR (the same rule
 * set the app reactor will enforce once the CR lands — the jobs and
 * institutions twins' own discipline).
 */
class KnowledgeModuleVerificationTest {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of("com.marketplace").verify();
    }
}

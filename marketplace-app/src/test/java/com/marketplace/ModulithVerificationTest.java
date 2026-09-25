package com.marketplace;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * S9: the skip below is NOT silent — {@link ModulithGuardReadinessTest}
 * fails the build on any runtime where this verification would be skipped,
 * so module-boundary enforcement can never quietly disappear. The pairing
 * this build resolves (archunit 1.4.2 via spring-modulith-core 2.1.1) does
 * not support JDK 26 class files (major 70); the readiness gate carries the
 * supported remediation paths.
 */
class ModulithVerificationTest {

    @Test
    void verifyModulesAndWriteDocs() {
        // ArchUnit/ASM reads bytecode + JDK classes; the aligned 1.4.2 pairing
        // does not support JDK 26 (class file major 70), so verification skips
        // on such runtimes — loudly, via ModulithGuardReadinessTest.
        assumeTrue(Runtime.version().feature() < 26, "Modulith verification requires ArchUnit with JDK 26 support.");

        var modules = ApplicationModules.of(MarketplaceApplication.class);
        modules.verify();
        new Documenter(modules).writeDocumentation();
    }
}

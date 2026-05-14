package com.marketplace;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithVerificationTest {

    @Test
    void verifyModulesAndWriteDocs() {
        // Spring Modulith uses ArchUnit/ASM to read bytecode + JDK classes.
        // As of today, ArchUnit versions pulled by Modulith don't support JDK 26 (class file major 70),
        // so we skip verification when running tests on JDK 26+.
        assumeTrue(Runtime.version().feature() < 26, "Modulith verification requires ArchUnit with JDK 26 support.");

        var modules = ApplicationModules.of(MarketplaceApplication.class);
        modules.verify();
        new Documenter(modules).writeDocumentation();
    }
}


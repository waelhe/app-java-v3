package com.marketplace;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithDocumentationTest {

    @Test
    void writeDocumentation() {
        // S9: not silent — ModulithGuardReadinessTest fails the build on any
        // runtime where this skip fires (the archunit 1.4.2 pairing resolved
        // by spring-modulith-core 2.1.1 lacks JDK 26 class-file support).
        assumeTrue(Runtime.version().feature() < 26, "Modulith docs require ArchUnit with JDK 26 support.");

        var modules = ApplicationModules.of(MarketplaceApplication.class);
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}

package com.marketplace;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithDocumentationTest {

    @Test
    void writeDocumentation() {
        assumeTrue(Runtime.version().feature() < 26, "Modulith docs require ArchUnit with JDK 26 support.");

        var modules = ApplicationModules.of(MarketplaceApplication.class);
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}

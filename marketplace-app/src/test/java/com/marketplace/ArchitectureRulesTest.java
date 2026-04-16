package com.marketplace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@AnalyzeClasses(packages = "com.marketplace", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule modulesMustBeCycleFree =
            slices().matching("com.marketplace.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersMustNotAccessRepositoriesDirectly =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository");

    @ArchTest
    static final ArchRule controllersMustNotAccessPlatformInfra =
            noClasses().that().haveSimpleNameEndingWith("Controller")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.marketplace.shared.jpa..",
                            "com.marketplace.shared.observability..");

    @ArchTest
    static final ArchRule servicesMustNotAccessControllers =
            noClasses().that().haveSimpleNameEndingWith("Service")
                    .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller");

    @ArchTest
    static final ArchRule sharedMustRemainPure =
            noClasses().that().resideInAPackage("com.marketplace.shared..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.marketplace.identity..",
                            "com.marketplace.catalog..",
                            "com.marketplace.booking..",
                            "com.marketplace.payments..",
                            "com.marketplace.pricing..",
                            "com.marketplace.reviews..",
                            "com.marketplace.messaging..",
                            "com.marketplace.search..",
                            "com.marketplace.admin..");

    @ArchTest
    static final ArchRule onlyAppShouldContainSpringBootApplication =
            classes().that().areAnnotatedWith(SpringBootApplication.class)
                    .should().haveSimpleName("MarketplaceApplication");
}

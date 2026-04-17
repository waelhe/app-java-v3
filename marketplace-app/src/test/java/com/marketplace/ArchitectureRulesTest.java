package com.marketplace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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

    @ArchTest
    static final ArchRule jpaRepositoriesMustNotBeManuallyEnabled =
            noClasses().that().resideInAnyPackage("com.marketplace..")
                    .should().beAnnotatedWith(EnableJpaRepositories.class)
                    .because("Boot auto-configures JPA repositories via DataJpaRepositoriesAutoConfiguration");

    @ArchTest
    static final ArchRule noCustomDatabaseHealthIndicator =
            noClasses().should().haveSimpleName("DatabaseHealthIndicator")
                    .because("Boot provides DataSourceHealthIndicator via DataSourceHealthContributorAutoConfiguration");

    @ArchTest
    static final ArchRule infraMustNotDependOnDomainModules =
            noClasses().that().resideInAnyPackage(
                            "com.marketplace.shared.jpa..",
                            "com.marketplace.shared.observability..",
                            "com.marketplace.shared.resilience..",
                            "com.marketplace.shared.security..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.marketplace.identity..",
                            "com.marketplace.catalog..",
                            "com.marketplace.booking..",
                            "com.marketplace.payments..",
                            "com.marketplace.pricing..",
                            "com.marketplace.reviews..",
                            "com.marketplace.messaging..",
                            "com.marketplace.search..")
                    .because("platform-infra must remain a leaf module with no dependency on domain modules");

    private static JavaClasses importProductionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests.class)
                .importPackages("com.marketplace");
    }

    private static boolean hasAnnotationOnAnyClass(JavaClasses classes, Class<? extends Annotation> annotation) {
        return classes.stream().anyMatch(c -> c.isAnnotatedWith(annotation));
    }

    private static boolean hasAnnotationOnAnyMethod(JavaClasses classes, Class<? extends Annotation> annotation) {
        return classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .anyMatch(m -> m.isAnnotatedWith(annotation));
    }

    @Test
    @DisplayName("No @EnableScheduling without @Scheduled usage")
    void enableSchedulingRequiresScheduledMethods() {
        var classes = importProductionClasses();
        if (hasAnnotationOnAnyClass(classes, EnableScheduling.class)
                && !hasAnnotationOnAnyMethod(classes, Scheduled.class)) {
            fail("@EnableScheduling is declared but no @Scheduled methods exist. Remove @EnableScheduling until scheduling is actually needed.");
        }
    }

    @Test
    @DisplayName("No @EnableAsync without @Async usage")
    void enableAsyncRequiresAsyncMethods() {
        var classes = importProductionClasses();
        if (hasAnnotationOnAnyClass(classes, EnableAsync.class)
                && !hasAnnotationOnAnyMethod(classes, Async.class)) {
            fail("@EnableAsync is declared but no @Async methods exist. Remove @EnableAsync until async processing is actually needed.");
        }
    }
}

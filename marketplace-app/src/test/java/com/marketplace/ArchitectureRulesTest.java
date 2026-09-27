package com.marketplace;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

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
    static final ArchRule controllersMustNotDependOnJpaEntities =
            noClasses().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
                    .because("the HTTP boundary speaks DTO records only — a JPA entity in a controller "
                            + "signature couples the wire contract to the persistence schema (lazy-loading "
                            + "semantics included) and lets schema evolution break the API. Audit 2026-09-25 "
                            + "finding 1: MessagingWebSocketController imported the Message entity and four "
                            + "controllers exposed entities in 9 REST signatures; this rule makes the "
                            + "repaired boundary regression-proof (Spring Modulith module-API model: "
                            + "published interfaces and DTOs, entities internal).");

    /**
     * Track 5 (plan e803a53): the transaction prohibitions the coding
     * standard states (CODING_STANDARDS.md §3.2) become permanent build
     * gates instead of waiting for review. Measured 2026-09-28 on 0a950ca:
     * 0 controller usages, 0 private-method usages — both green today by
     * measurement, not by hope.
     */
    @ArchTest
    static final ArchRule transactionsMustNotLiveOnControllers =
            classes().that().areAnnotatedWith(RestController.class)
                    .or().areAnnotatedWith(Controller.class)
                    .or().haveSimpleNameEndingWith("Controller")
                    .should(notUseTransactionalAnywhere())
                    .because("@Transactional on the web layer starts a transaction in the controller — "
                            + "the service layer owns transaction boundaries (Spring Framework reference, "
                            + "Declarative Transaction Management: @Transactional declares the transactional "
                            + "boundary of a business operation; CODING_STANDARDS.md §3.2 prohibits it on "
                            + "controllers). 0 usages measured at adoption (2026-09-28).");

    @ArchTest
    static final ArchRule transactionsMustNotBePrivate =
            classes().that().resideInAPackage("com.marketplace..")
                    .should(notHaveTransactionalOnPrivateMethods())
                    .because("proxy-based AOP makes @Transactional on a non-public method a silent no-op — "
                            + "the Spring Framework reference (Using @Transactional) is explicit: only "
                            + "public methods invoked through the proxy get the advice; a private "
                            + "@Transactional method runs WITHOUT a transaction while reading as if it "
                            + "had one. CODING_STANDARDS.md §3.2 states the same prohibition. "
                            + "0 usages measured at adoption (2026-09-28).");

    /**
     * Track 5 (plan e803a53): the shared ports stay framework-neutral. Red
     * with exactly the two measured offenders (CatalogSearchPort,
     * RealestatePropertyFilterPort importing Spring Data Page/Pageable)
     * before this PR's repair; green after — and permanent.
     */
    @ArchTest
    static final ArchRule sharedPortsAreFrameworkNeutral =
            noClasses().that().resideInAPackage("com.marketplace.shared.api..")
                    .and().haveSimpleNameEndingWith("Port")
                    .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                    .because("the shared-contract module is the last place framework types should leak "
                            + "(Spring Modulith boundary model: the module API is what consumers depend "
                            + "on — a port speaking Spring Data's Pageable drags every consumer module "
                            + "into that type system). Audit 2026-09-25: 2 of 24 ports imported "
                            + "org.springframework.data.domain — repaired in the same change that "
                            + "installed this rule; the neutral contracts are PagedRequest/PagedResponse "
                            + "with SpringPagination as the documented interop corner.");

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
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.marketplace");
    }

    private static ArchCondition<JavaClass> notUseTransactionalAnywhere() {
        return new ArchCondition<>("not use @Transactional on the class or any method") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean classLevel = clazz.isAnnotatedWith(Transactional.class);
                boolean methodLevel = clazz.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(Transactional.class));
                if (classLevel || methodLevel) {
                    events.add(SimpleConditionEvent.violated(clazz, String.format(
                            "%s carries @Transactional in the web layer — the service layer owns "
                                    + "transaction boundaries (Spring Framework reference, Declarative "
                                    + "Transaction Management; CODING_STANDARDS.md §3.2)",
                            clazz.getName())));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notHaveTransactionalOnPrivateMethods() {
        return new ArchCondition<>("not carry @Transactional on a private method") {
            @Override
            public void check(JavaClass clazz, ConditionEvents events) {
                boolean violated = clazz.getMethods().stream()
                        .anyMatch(method -> method.isAnnotatedWith(Transactional.class)
                                && method.getModifiers().contains(JavaModifier.PRIVATE));
                if (violated) {
                    events.add(SimpleConditionEvent.violated(clazz, String.format(
                            "%s has a private @Transactional method — proxy-based AOP does not advise "
                                    + "it, so the method silently runs without a transaction while "
                                    + "reading as if it had one (Spring Framework reference, Using "
                                    + "@Transactional; CODING_STANDARDS.md §3.2)",
                            clazz.getName())));
                }
            }
        };
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

package com.marketplace.availability;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row 11 (agent findings 2026-10-25 — CWE-834): the slot publication
 * bound is {@code @Min(1)} on {@link AvailabilityProperties#maxSlotDays()}
 * — a non-positive environment value must fail STARTUP at binding time,
 * never come up rejecting every slot (the "bound satisfied by zero"
 * trap). The official recipe: «Spring Boot attempts to validate
 * @ConfigurationProperties classes whenever they are annotated with
 * Spring's @Validated annotation» (Spring Boot reference — Type-safe
 * Configuration Properties — Validation).
 *
 * <p>The runner registers the module's real {@link AvailabilityConfig} —
 * the exact wiring the application uses (the SearchPropertiesValidationTest
 * pattern). The compatibility contract with the pricing bound
 * ({@code PricingProperties.DEFAULT_MAX_STAY_NIGHTS}) is pinned by the
 * equal defaults asserted here.
 */
class AvailabilityPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(AvailabilityConfig.class));

    @Test
    void absentBoundBindsToTheDocumentedDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            // The compatibility contract with the pricing stay bound
            // (equal defaults, deployments move them together) is a
            // DOCUMENTED contract — the module boundary forbids depending
            // on the pricing module's class here, so this pins the default
            // by its own documented value only.
            assertThat(context.getBean(AvailabilityProperties.class).maxSlotDays())
                    .isEqualTo(AvailabilityProperties.DEFAULT_MAX_SLOT_DAYS)
                    .isEqualTo(365L);
        });
    }

    @Test
    void explicitBoundBinds() {
        runner.withPropertyValues("marketplace.availability.max-slot-days=30")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AvailabilityProperties.class).maxSlotDays())
                            .isEqualTo(30L);
                });
    }

    @Test
    void zeroBoundFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.availability.max-slot-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    List<Throwable> chain = new ArrayList<>();
                    for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                        chain.add(t);
                    }
                    assertThat(chain).anyMatch(BindValidationException.class::isInstance);
                    assertThat(chain.stream().map(Throwable::getMessage)
                            .filter(m -> m != null).toList())
                            .anySatisfy(m -> assertThat(m).contains("maxSlotDays"));
                });
    }

    @Test
    void negativeBoundFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.availability.max-slot-days=-7")
                .run(context -> assertThat(context).hasFailed());
    }
}

package com.marketplace.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Row 11 (agent findings 2026-10-25 — CWE-834): the day-sliced model's
 * stay bound is {@code @Min(1)} on {@link PricingProperties#maxStayNights()}
 * — a non-positive environment value must fail STARTUP at binding time,
 * never come up rejecting every window (the "bound satisfied by zero"
 * trap). The official recipe: «Spring Boot attempts to validate
 * @ConfigurationProperties classes whenever they are annotated with
 * Spring's @Validated annotation» (Spring Boot reference — Type-safe
 * Configuration Properties — Validation).
 *
 * <p>The runner registers the module's real {@link PricingConfig} — the
 * exact wiring the application uses (the SearchPropertiesValidationTest
 * pattern).
 */
class PricingPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(PricingConfig.class));

    @Test
    void absentBoundBindsToTheDocumentedDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PricingProperties.class).maxStayNights())
                    .isEqualTo(PricingProperties.DEFAULT_MAX_STAY_NIGHTS)
                    .isEqualTo(365L);
        });
    }

    @Test
    void explicitBoundBinds() {
        runner.withPropertyValues("marketplace.pricing.max-stay-nights=90")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PricingProperties.class).maxStayNights())
                            .isEqualTo(90L);
                });
    }

    @Test
    void zeroBoundFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.pricing.max-stay-nights=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    List<Throwable> chain = new ArrayList<>();
                    for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                        chain.add(t);
                    }
                    assertThat(chain).anyMatch(BindValidationException.class::isInstance);
                    assertThat(chain.stream().map(Throwable::getMessage)
                            .filter(m -> m != null).toList())
                            .anySatisfy(m -> assertThat(m).contains("maxStayNights"));
                });
    }

    @Test
    void negativeBoundFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.pricing.max-stay-nights=-7")
                .run(context -> assertThat(context).hasFailed());
    }
}

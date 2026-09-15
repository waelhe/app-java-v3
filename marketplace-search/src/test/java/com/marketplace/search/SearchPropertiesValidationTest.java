package com.marketplace.search;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CodeRabbit round-2 adoption (L35): the saved-search cap is
 * {@code @Min(1)} on {@link SearchProperties.SavedSearches#maxPerUser()} —
 * a non-positive environment value must fail STARTUP at binding time, not
 * surface as a blanket 409 on every otherwise-valid create. The official
 * recipe: «Spring Boot attempts to validate @ConfigurationProperties
 * classes whenever they are annotated with Spring's @Validated annotation
 * … To cascade validation to nested properties the associated field must
 * be annotated with @Valid» (Spring Boot reference — Type-safe
 * Configuration Properties — Validation).
 *
 * <p>The runner registers the module's real {@link SearchConfig}
 * ({@code @EnableConfigurationProperties(SearchProperties.class)} — the
 * MessagingConfig house pattern) so the guard exercises the exact wiring
 * the application uses, including the {@code @Valid} cascade into the
 * nested record. This is the boundary case the reviewer asked for: the
 * concurrent-cap thread (already adopted) proves the cap cannot be
 * RACED past its value; this guard proves the value itself cannot be
 * deployed non-positive.
 */
class SearchPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(SearchConfig.class));

    @Test
    void absentSectionBindsToTheConservativeDefaultCap() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SearchProperties.class)
                    .savedSearches().maxPerUser()).isEqualTo(20);
        });
    }

    @Test
    void positiveEnvironmentValueBinds() {
        runner.withPropertyValues("marketplace.search.savedsearches.max-per-user=5")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SearchProperties.class)
                            .savedSearches().maxPerUser()).isEqualTo(5);
                });
    }

    @Test
    void zeroCapFailsStartupAtBindingTimeWithTheCascadePath() {
        runner.withPropertyValues("marketplace.search.savedsearches.max-per-user=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The binding failure must be VALIDATION (not any other
                    // startup error), and the violation must name the nested
                    // field through the @Valid cascade.
                    List<Throwable> chain = new ArrayList<>();
                    for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
                        chain.add(t);
                    }
                    assertThat(chain).anyMatch(BindValidationException.class::isInstance);
                    assertThat(chain.stream().map(Throwable::getMessage)
                            .filter(m -> m != null).toList())
                            .anySatisfy(m -> assertThat(m).contains("maxPerUser"));
                });
    }

    @Test
    void negativeCapFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.search.savedsearches.max-per-user=-3")
                .run(context -> assertThat(context).hasFailed());
    }
}

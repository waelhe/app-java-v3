package com.marketplace.app.graphql;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GraphQL surface's typed-binding gate (the SearchPropertiesValidationTest
 * pattern): the bounded-collection and query-cost budgets are operational
 * contract, so a non-deployable value must fail STARTUP at binding time —
 * never surface as an unbounded read or an unenforced limit at runtime.
 *
 * <p>The runner registers the real {@link GraphQlConfig}
 * ({@code @EnableConfigurationProperties(GraphQlProperties.class)} — the
 * module-local house pattern), so the guard exercises the exact wiring the
 * application uses, including the {@code @Valid} cascade into the nested
 * records (the official validation contract: «Spring Boot attempts to
 * validate @ConfigurationProperties classes whenever they are annotated
 * with Spring's @Validated annotation … To cascade validation to nested
 * properties the associated field must be annotated with @Valid»).
 */
class GraphQlPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(GraphQlConfig.class));

    /**
     * Absent sections bind to the {@code @DefaultValue}-primed safe
     * defaults — the constructor-binding house rule (an absent nested
     * section would otherwise bind to null), so every budget has a value
     * without any configuration line.
     */
    @Test
    void absentSectionsBindToTheSafeDefaults() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            GraphQlProperties properties = context.getBean(GraphQlProperties.class);
            assertThat(properties.errors().includeTraceId()).isFalse();
            assertThat(properties.pagination().defaultPageSize()).isEqualTo(20);
            assertThat(properties.pagination().maxPageSize()).isEqualTo(100);
            assertThat(properties.limits().maxQueryDepth()).isEqualTo(10);
            assertThat(properties.limits().maxQueryComplexity()).isEqualTo(200);
        });
    }

    /**
     * Environment variables bind through Boot's relaxed naming into the
     * typed record — every section and key, proving the env-tunable
     * contract (G-R6) without a rebuild.
     */
    @Test
    void environmentValuesBindThroughRelaxedNaming() {
        runner.withPropertyValues(
                        "marketplace.graphql.errors.include-trace-id=true",
                        "marketplace.graphql.pagination.default-page-size=5",
                        "marketplace.graphql.pagination.max-page-size=25",
                        "marketplace.graphql.limits.max-query-depth=4",
                        "marketplace.graphql.limits.max-query-complexity=40")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GraphQlProperties properties = context.getBean(GraphQlProperties.class);
                    assertThat(properties.errors().includeTraceId()).isTrue();
                    assertThat(properties.pagination().defaultPageSize()).isEqualTo(5);
                    assertThat(properties.pagination().maxPageSize()).isEqualTo(25);
                    assertThat(properties.limits().maxQueryDepth()).isEqualTo(4);
                    assertThat(properties.limits().maxQueryComplexity()).isEqualTo(40);
                });
    }

    /**
     * A non-positive page size fails STARTUP at binding time through the
     * {@code @Validated} + {@code @Valid} cascade (BindValidationException
     * naming the field) — a non-deployable budget never surfaces as an
     * unbounded read at runtime.
     */
    @Test
    void nonPositivePageSizeFailsStartupAtBindingTime() {
        // default-page-size=0 trips the @Min(1) constraint CLEANLY: the
        // compact-constructor invariant (default must not exceed max) stays
        // silent, so the startup failure is VALIDATION — proving the
        // @Validated + @Valid cascade path is live for the pagination
        // section (the max=0 shape would fail through the record invariant
        // instead, which is the separate test below).
        runner.withPropertyValues("marketplace.graphql.pagination.default-page-size=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureChain(context)).anyMatch(BindValidationException.class::isInstance);
                    assertThat(failureChain(context).stream().map(Throwable::getMessage)
                            .filter(m -> m != null).toList())
                            .satisfies(__lst -> assertThat(__lst).anySatisfy(m -> assertThat(m).contains("defaultPageSize")));
                });
    }

    /**
     * The complexity budget twin: a non-positive max-query-complexity
     * fails startup at binding time with the validation exception naming
     * the field.
     */
    @Test
    void nonPositiveComplexityBudgetFailsStartupAtBindingTime() {
        runner.withPropertyValues("marketplace.graphql.limits.max-query-complexity=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureChain(context)).anyMatch(BindValidationException.class::isInstance);
                    assertThat(failureChain(context).stream().map(Throwable::getMessage)
                            .filter(m -> m != null).toList())
                            .satisfies(__lst -> assertThat(__lst).anySatisfy(m -> assertThat(m).contains("maxQueryComplexity")));
                });
    }

    /**
     * The default page size may not exceed the hard maximum — a deployment
     * where the "default" widens past the ceiling would make the default
     * itself the unbounded path. The record's compact constructor rejects
     * it at binding time.
     */
    @Test
    void defaultPageSizeAboveTheMaximumIsRejectedByTheRecordInvariant() {
        runner.withPropertyValues(
                        "marketplace.graphql.pagination.default-page-size=200",
                        "marketplace.graphql.pagination.max-page-size=100")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(failureChain(context))
                            .anyMatch(t -> t instanceof IllegalArgumentException
                                    && t.getMessage().contains("must not exceed"));
                });
    }

    private static List<Throwable> failureChain(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}

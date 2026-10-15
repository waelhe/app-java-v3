package com.marketplace.app.graphql;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The GraphQL surface's operational wiring — the module-local
 * {@code @Configuration} + {@code @EnableConfigurationProperties} pattern
 * of {@code SearchConfig}/{@code PaymentsChannelConfig} (SYSTEM.md §8).
 *
 * <p><b>Why these beans are plain {@code Instrumentation} beans (the
 * officially supported registration path, measured in spring-boot-graphql
 * 4.1.1's {@code GraphQlAutoConfiguration#graphQlSource} bytecode):</b>
 * Boot collects every {@code Instrumentation} bean in the context through
 * an {@code ObjectProvider} and passes the composed list to
 * {@code GraphQlSource.Builder#instrumentation(List)} — the same list also
 * receives the auto-configured {@code GraphQlObservationInstrumentation}
 * (spring-boot-graphql's {@code GraphQlObservationAutoConfiguration} is
 * {@code @ConditionalOnBean(ObservationRegistry)}), so the limit
 * instrumentations compose with observability instead of replacing it.</p>
 *
 * <p>The instrumentations themselves are graphql-java 25.0's own
 * {@code graphql.analysis} classes (resolved transitively by the Boot
 * 4.1.1 BOM — spring-graphql 2.0.5): {@code MaxQueryDepthInstrumentation}
 * aborts with {@code ErrorType.ExecutionAborted} during validation when the
 * query tree exceeds the depth budget, and {@code MaxQueryComplexityInstrumentation}
 * does the same for the weighted-complexity budget using the documented
 * default calculator {@code 1 + childComplexity} per field. No custom
 * calculator, no independent version pin — the design plan's §5.7 rule:
 * verify the APIs of the exact resolved version, do not pin around them.</p>
 */
@Configuration
@EnableConfigurationProperties(GraphQlProperties.class)
public class GraphQlConfig {

    @Bean
    public Instrumentation maxQueryDepthInstrumentation(GraphQlProperties properties) {
        return new MaxQueryDepthInstrumentation(properties.limits().maxQueryDepth());
    }

    @Bean
    public Instrumentation maxQueryComplexityInstrumentation(GraphQlProperties properties) {
        return new MaxQueryComplexityInstrumentation(properties.limits().maxQueryComplexity());
    }
}

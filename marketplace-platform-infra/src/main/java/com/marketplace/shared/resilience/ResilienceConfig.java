package com.marketplace.shared.resilience;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Enables Spring Framework 7.0 core resilience features: {@code @Retryable} and
 * {@code @ConcurrencyLimit}.
 *
 * <p>Official reference:
 * <a href="https://docs.spring.io/spring-framework/reference/core/resilience.html">
 * Spring Framework — Resilience Features</a>
 *
 * <p>{@code @ConcurrencyLimit} is particularly important with Virtual Threads
 * (enabled via {@code spring.threads.virtual.enabled=true}), because there is
 * no thread-pool limit by default — this annotation protects critical resources
 * from unbounded concurrent access.
 *
 * <p>Spring Boot auto-configures AOP support ({@code spring.aop.auto=true}),
 * so {@code @EnableAspectJAutoProxy} is not required.
 */
@Configuration
@EnableResilientMethods
public class ResilienceConfig {
}

package com.marketplace.shared.resilience;

import io.github.resilience4j.retry.RetryConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The shared Resilience4j {@code Retry} instance for the insert-race recovery
 * (roadmap gap G-RETRY-1): every "upsert via unique constraint" writer that must
 * retry its write in a NEW transaction after a lost insert race now declares
 * {@code @Retry(name = InsertRaceRetryConfiguration.INSERT_RACE_RETRY)} instead
 * of hand-coding a try/catch re-call.
 *
 * <p><b>Why programmatic and not YAML:</b> the resilience4j Spring Boot starter's
 * auto-configured registry only registers instances declared under
 * {@code resilience4j.retry.instances.*} AND referenced by a supported
 * annotation/Bean factory — a plain {@code @Retry(name = "...")} without a
 * matching YAML instance fails at proxy creation ("Retry 'x' not configured").
 * A {@code RetryConfig} bean named {@code "<name>RetryConfig"} is the official
 * programmatic recipe (resilience4j README, "How to create RetryConfig
 * programmatically"; the starter's {@code RetryConfigurationBeans} picks up
 * {@code Map<String, RetryConfig>} from the context), keeping the knobs out of
 * YAML duplication while binding them through {@link InsertRaceRetryProperties}.
 *
 * <p><b>Why retrying {@link DataIntegrityViolationException} is safe here:</b>
 * the callers wrap ONLY their own dedicated {@code REQUIRES_NEW} upsert
 * transaction with this annotation, so by the time the exception surfaces the
 * failed transaction has already rolled back (PostgreSQL aborted it at the
 * constraint violation); the retry therefore always runs against fresh
 * transaction state — never a poisoned one. The semantics are identical to the
 * manual catch-and-retry this class replaces: exactly the same exception is the
 * retry trigger, and any non-insert-race integrity failure simply exhausts the
 * bounded attempts and propagates (the same loud-failure contract).
 *
 * <p><b>Proxy mechanics (Spring Framework reference, Proxying Modes /
 * Transactional services):</b> {@code @Retry} on a {@code private} method would
 * be a silent no-op (self-invocation bypasses the proxy). Every annotated
 * method below is therefore PUBLIC and invoked THROUGH the injected bean
 * reference — never via {@code this}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InsertRaceRetryProperties.class)
public class InsertRaceRetryConfiguration {

    /** The single instance name shared by all insert-race writers. */
    public static final String INSERT_RACE_RETRY = "insertRaceRetry";

    @Bean(name = INSERT_RACE_RETRY + "RetryConfig")
    public RetryConfig insertRaceRetryConfig(InsertRaceRetryProperties properties) {
        return RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                // Official resilience4j API (verified against the 2.4.0
                // bytecode): IntervalFunction.ofExponentialBackoff(Duration,
                // double) is the documented exponential-backoff factory;
                // Builder.intervalFunction(...) installs it as the wait
                // strategy. No manual waitDuration + bi-function override —
                // that combination would silently conflict in the config.
                .intervalFunction(io.github.resilience4j.core.IntervalFunction
                        .ofExponentialBackoff(properties.waitDuration(), properties.multiplier()))
                .retryOnException(DataIntegrityViolationException.class::isInstance)
                .build();
    }
}

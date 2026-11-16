package com.marketplace.shared.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mirrors the aggregate {@code /actuator/health} verdict into the
 * {@code marketplace.health.aggregate} gauge (Micrometer) so the platform's own
 * health statement is consumable through the metrics pipeline and the alert
 * rules committed under {@code monitoring/prometheus-rules/} (guarded by
 * {@code AlertRulesYamlTest} in marketplace-app).
 *
 * <p>Why this gauge exists (plan item 3.2 — the S10 lesson, measured 2026-09-26):
 * the aggregate endpoint is the only place where every health contributor meets
 * — readiness and liveness are deliberately narrow (a stale event bus or, before
 * the S10 fix, an auxiliary mail channel can leave the aggregate DOWN while
 * every probe stays green). Relying on HTTP probes alone means the aggregate is
 * inspected only at the watchdog's 15-minute cadence — far too sparse to keep a
 * metric fresh inside the 5-minute {@code for:} window of
 * {@code MarketplaceAggregateHealthDown}. The gauge therefore refreshes itself,
 * exactly like {@code ModulithEventBusHealthIndicator} does for its own verdict.
 *
 * <p>Value semantics (mirroring the eventbus convention):
 * {@code 1} = UP (every contributor healthy); {@code 0} = the aggregate verdict
 * is DOWN / OUT_OF_SERVICE / UNKNOWN — some contributor is failing, which after
 * mail was taken out of platform health ({@code management.health.mail.enabled:
 * false}) means a core platform dependency; {@code -1} = the probe itself failed
 * (the {@link HealthEndpoint} call threw — page on the same {@code != 1} rule: a
 * frozen last-good value would masquerade as health).
 *
 * <p>The gauge is read from the official {@link HealthEndpoint} bean
 * ({@code health()} returns the aggregate {@code HealthDescriptor} — the same
 * object the HTTP endpoint serves), so it can never drift from what
 * {@code GET /actuator/health} actually answers. The {@link MeterRegistry} is
 * injected via {@link ObjectProvider} and is strictly optional, mirroring the
 * sibling indicator: contexts that do not expose one keep constructing this
 * component without the gauge.
 */
@Component
public class AggregateHealthGauge {

    /** Mirrored by alert rule {@code MarketplaceAggregateHealthDown}. */
    static final String AGGREGATE_HEALTH_METRIC = "marketplace.health.aggregate";

    private final HealthEndpoint healthEndpoint;
    private final AtomicLong aggregateStatus = new AtomicLong(1);

    public AggregateHealthGauge(HealthEndpoint healthEndpoint,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.healthEndpoint = healthEndpoint;
        meterRegistry.stream().findFirst().ifPresent(registry -> Gauge
                .builder(AGGREGATE_HEALTH_METRIC, aggregateStatus, AtomicLong::doubleValue)
                .description("Aggregate /actuator/health verdict: 1 = UP, 0 = a contributor "
                        + "is DOWN/OUT_OF_SERVICE/UNKNOWN, -1 = the probe itself failed")
                .register(registry));
    }

    /**
     * Keeps {@code marketplace.health.aggregate} fresh without relying on HTTP
     * probes (see class javadoc for why the watchdog's 15-minute cadence cannot
     * substitute). 60s keeps several gauge samples inside the 5m {@code for:}
     * window of {@code MarketplaceAggregateHealthDown}; the scheduling style
     * matches {@code ModulithEventBusHealthIndicator} (scheduling itself is
     * activated app-wide by {@code CacheConfig}'s {@code @EnableScheduling}).
     */
    @Scheduled(fixedDelay = 60, initialDelay = 30, timeUnit = TimeUnit.SECONDS)
    void refreshAggregateHealthGauge() {
        try {
            Status status = healthEndpoint.health().getStatus();
            aggregateStatus.set(Status.UP.equals(status) ? 1L : 0L);
        } catch (Exception e) {
            // -1 marks UNKNOWN (see class javadoc): a failed probe must not
            // freeze the last-good value — the alert rule treats any value
            // other than 1 as paging.
            aggregateStatus.set(-1L);
        }
    }
}

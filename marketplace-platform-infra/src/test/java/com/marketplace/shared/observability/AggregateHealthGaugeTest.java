package com.marketplace.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.DefaultReactiveHealthContributorRegistry;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Unit tests for {@link AggregateHealthGauge}.
 *
 * <p><b>Why the descriptors are real, not mocks (measured against the official
 * jar):</b> {@code HealthDescriptor} is a <b>sealed</b> abstract class in
 * spring-boot-health 4.1.1 whose two permitted subclasses
 * ({@code IndicatedHealthDescriptor} — final, {@code CompositeHealthDescriptor})
 * both hide their constructors at package scope. Mockito officially refuses to
 * mock sealed/abstract types ("Sealed interfaces or abstract classes can't be
 * mocked"), and the JVM refuses any other subclass. The only honest entry
 * point is therefore the production path itself: a real {@link HealthEndpoint}
 * built from the public API ({@code DefaultHealthContributorRegistry} +
 * {@code HealthEndpointGroups.of(group, Map.of())}), aggregating contributors
 * whose {@link Health} we control. This makes every test here exercise the
 * exact aggregation semantics the gauge will read in production — the same
 * {@link StatusAggregator#getDefault()} verdict that serves
 * {@code GET /actuator/health} — instead of a hand-stubbed stand-in.</p>
 *
 * <p>The one behaviour that cannot be produced through the real path is the
 * gauge's own probe failing (the {@code HealthEndpoint.health()} call throwing)
 * — the framework contains contributor exceptions inside {@link Health} (that
 * containment is itself asserted below as a DOWN verdict, value 0). The
 * throwing-endpoint case uses a Mockito mock of the {@code HealthEndpoint}
 * class (a regular, mockable class — only the descriptor is sealed).</p>
 */
class AggregateHealthGaugeTest {

    @Test
    void gaugeIsOneWhenTheAggregateVerdictIsUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HealthEndpoint endpoint = endpointContributing(Status.UP);

        new AggregateHealthGauge(endpoint, providerOf(registry)).refreshAggregateHealthGauge();

        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    void gaugeIsZeroWhenAContributorDragsTheAggregateDown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HealthEndpoint endpoint = endpointContributing(Status.DOWN);

        new AggregateHealthGauge(endpoint, providerOf(registry)).refreshAggregateHealthGauge();

        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void gaugeReadsTheAggregatedVerdictNotASingleContributor() {
        // The S10 shape itself: one healthy contributor is NOT enough — the
        // aggregate verdict is the StatusAggregator's call over ALL of them.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HealthEndpoint endpoint = endpointContributing(Status.UP, Status.DOWN);

        new AggregateHealthGauge(endpoint, providerOf(registry)).refreshAggregateHealthGauge();

        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .as("one UP + one DOWN contributor must aggregate to DOWN")
                .isEqualTo(0.0);
    }

    @Test
    void gaugeIsZeroWhenTheVerdictIsOutOfServiceOrUnknown() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        var outOfServiceGauge = new AggregateHealthGauge(
                endpointContributing(Status.OUT_OF_SERVICE), providerOf(registry));
        outOfServiceGauge.refreshAggregateHealthGauge();
        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(0.0);

        var unknownGauge = new AggregateHealthGauge(
                endpointContributing(Status.UNKNOWN), providerOf(registry));
        unknownGauge.refreshAggregateHealthGauge();
        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void gaugeIsZeroWhenTheFailureIsContainedInsideAContributor() {
        // A contributor whose doHealthCheck throws is contained by
        // AbstractHealthIndicator into a DOWN Health — the aggregate verdict
        // is DOWN (gauge 0), NOT a probe failure (-1). Pinning this keeps the
        // -1 semantics reserved for "the HealthEndpoint call itself threw".
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AbstractHealthIndicator throwing = new AbstractHealthIndicator() {
            @Override
            protected void doHealthCheck(Health.Builder builder) throws Exception {
                throw new IllegalStateException("contributor failure is the aggregate's business");
            }
        };
        HealthEndpoint endpoint = endpointContributing(throwing);

        new AggregateHealthGauge(endpoint, providerOf(registry)).refreshAggregateHealthGauge();

        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .as("contained contributor failures are DOWN (0), not probe failure (-1)")
                .isEqualTo(0.0);
    }

    @Test
    void gaugeMarksUnknownMinusOneWhenTheProbeItselfFails() {
        // The S10-era lesson (CodeRabbit #239 convention, adopted by the sibling
        // indicator): a failed probe must not freeze the last-good value —
        // -1 pages via the != 1 alert rule instead of reading as health.
        // HealthEndpoint (a regular class) is mockable; only HealthDescriptor is
        // sealed — see the class javadoc. The good reading comes from the real
        // production path, the failure from sequential stubbing.
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HealthDescriptor upDescriptor = endpointContributing(Status.UP).health();
        HealthEndpoint failingEndpoint = mock(HealthEndpoint.class);
        when(failingEndpoint.health()).thenReturn(upDescriptor)
                .thenThrow(new RuntimeException("endpoint call failed"));

        var gauge = new AggregateHealthGauge(failingEndpoint, providerOf(registry));
        gauge.refreshAggregateHealthGauge();
        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(1.0);

        gauge.refreshAggregateHealthGauge();

        assertThat(registry.get(AggregateHealthGauge.AGGREGATE_HEALTH_METRIC).gauge().value())
                .isEqualTo(-1.0);
    }

    @Test
    void refreshIsRegisteredAsScheduledProbeInsideTheAlertWindow() throws Exception {
        // Scheduler wiring (mirrors the sibling indicator's guard): pins the
        // @Scheduled contract so the gauge cannot silently freeze. The probe
        // interval must sample well inside the 5m for: window of
        // MarketplaceAggregateHealthDown.
        var method = AggregateHealthGauge.class.getDeclaredMethod("refreshAggregateHealthGauge");
        var scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("@Scheduled must stay on the gauge refresh method").isNotNull();
        assertThat(scheduled.timeUnit()).isEqualTo(TimeUnit.SECONDS);
        assertThat(scheduled.fixedDelay()).isEqualTo(60);
        assertThat(scheduled.initialDelay()).isEqualTo(30);
        assertThat(scheduled.timeUnit().toMillis(scheduled.fixedDelay()))
                .as("probe interval must stay well inside the 5m alert window")
                .isLessThan(Duration.ofMinutes(5).toMillis());
    }

    @Test
    void constructsAndRefreshesWithoutAMeterRegistry() {
        HealthEndpoint endpoint = endpointContributing(Status.UP);

        var gauge = new AggregateHealthGauge(endpoint, emptyProvider());

        gauge.refreshAggregateHealthGauge();
        // No gauge exists to read — the point is that nothing throws: module
        // test slices without a MeterRegistry keep the component constructible
        // (and the endpoint itself still answers through the real path).
        assertThat(endpoint.health().getStatus()).isEqualTo(Status.UP);
    }

    /**
     * Builds a real {@link HealthEndpoint} — the production construction path,
     * entirely through public API — aggregating one contributor per given
     * status. {@code HealthEndpointGroups.of} needs a primary group; the
     * anonymous implementation below mirrors the default group semantics
     * (every contributor is a member, nothing extra shown, default aggregator
     * and HTTP code mapping).
     */
    private static HealthEndpoint endpointContributing(Status... statuses) {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        for (int i = 0; i < statuses.length; i++) {
            registry.registerContributor("probe" + i, indicatorOf(statuses[i]));
        }
        return new HealthEndpoint(registry, new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(allMembersGroup(), Map.of()), null);
    }

    private static HealthEndpoint endpointContributing(AbstractHealthIndicator indicator) {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        registry.registerContributor("probe0", indicator);
        return new HealthEndpoint(registry, new DefaultReactiveHealthContributorRegistry(),
                HealthEndpointGroups.of(allMembersGroup(), Map.of()), null);
    }

    private static AbstractHealthIndicator indicatorOf(Status status) {
        return new AbstractHealthIndicator() {
            @Override
            protected void doHealthCheck(Health.Builder builder) {
                builder.status(status);
            }
        };
    }

    private static HealthEndpointGroup allMembersGroup() {
        return new HealthEndpointGroup() {
            @Override
            public boolean isMember(String name) {
                return true;
            }

            @Override
            public boolean showComponents(SecurityContext securityContext) {
                return false;
            }

            @Override
            public boolean showDetails(SecurityContext securityContext) {
                return false;
            }

            @Override
            public StatusAggregator getStatusAggregator() {
                return StatusAggregator.getDefault();
            }

            @Override
            public HttpCodeStatusMapper getHttpCodeStatusMapper() {
                return HttpCodeStatusMapper.getDefault();
            }

            @Override
            public AdditionalHealthEndpointPath getAdditionalPath() {
                return null;
            }
        };
    }

    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                return registry;
            }

            @Override
            public MeterRegistry getObject() {
                return registry;
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return registry;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return registry;
            }

            @Override
            public Stream<MeterRegistry> stream() {
                return Stream.of(registry);
            }
        };
    }

    /** Resolves no registry (empty stream) — mirrors contexts without MeterRegistry. */
    private static ObjectProvider<MeterRegistry> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public MeterRegistry getObject(Object... args) {
                throw new UnsupportedOperationException("no registry in this context");
            }

            @Override
            public MeterRegistry getObject() {
                throw new UnsupportedOperationException("no registry in this context");
            }

            @Override
            public MeterRegistry getIfAvailable() {
                return null;
            }

            @Override
            public MeterRegistry getIfUnique() {
                return null;
            }

            @Override
            public Stream<MeterRegistry> stream() {
                return Stream.empty();
            }
        };
    }
}

package com.marketplace.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ModulithEventBusHealthIndicatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    /** Resolves no registry (empty stream) — mirrors contexts without MeterRegistry. */
    @Mock
    private ObjectProvider<MeterRegistry> emptyMeterRegistry;

    @InjectMocks
    private ModulithEventBusHealthIndicator healthIndicator;

    @Test
    void healthIsUpWhenNoStaleEvents() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(0);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthIsDownWhenStaleEventsExist() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(3);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("staleEventCount", 3);
        assertThat(health.getDetails()).containsEntry("thresholdSeconds", 21600L);
    }

    @Test
    void healthIsUpWhenNullCount() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(null);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void healthIsDownWhenJdbcThrows() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenThrow(
                new RuntimeException("DB connection failed"));

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void gaugeMirrorsStaleCountWhenMeterRegistryIsAvailable() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var indicator = new ModulithEventBusHealthIndicator(jdbcTemplate, providerOf(registry));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(3);

        indicator.health();

        assertThat(registry.get(ModulithEventBusHealthIndicator.STALE_PUBLICATIONS_METRIC).gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void gaugeIsZeroWhenNoStaleEvents() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var indicator = new ModulithEventBusHealthIndicator(jdbcTemplate, providerOf(registry));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(0);

        indicator.health();

        assertThat(registry.get(ModulithEventBusHealthIndicator.STALE_PUBLICATIONS_METRIC).gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void scheduledRefreshUpdatesGaugeWithoutHealthEndpoint() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var indicator = new ModulithEventBusHealthIndicator(jdbcTemplate, providerOf(registry));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(3);

        indicator.refreshStalePublicationsGauge();

        assertThat(registry.get(ModulithEventBusHealthIndicator.STALE_PUBLICATIONS_METRIC).gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void scheduledRefreshKeepsLastGaugeValueWhenQueryFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        var indicator = new ModulithEventBusHealthIndicator(jdbcTemplate, providerOf(registry));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L)))
                .thenReturn(3)
                .thenThrow(new RuntimeException("DB connection failed"));

        indicator.refreshStalePublicationsGauge();
        indicator.refreshStalePublicationsGauge();

        assertThat(registry.get(ModulithEventBusHealthIndicator.STALE_PUBLICATIONS_METRIC).gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void healthReportsUpWithoutMeterRegistry() {
        var indicator = new ModulithEventBusHealthIndicator(jdbcTemplate, emptyMeterRegistry);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(21600L))).thenReturn(0);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    private static ObjectProvider<MeterRegistry> providerOf(MeterRegistry registry) {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerSingleton("meterRegistry", registry);
        return beanFactory.getBeanProvider(MeterRegistry.class);
    }
}

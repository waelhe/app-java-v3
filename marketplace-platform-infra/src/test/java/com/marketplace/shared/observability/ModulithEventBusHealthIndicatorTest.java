package com.marketplace.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class ModulithEventBusHealthIndicatorTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

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
}

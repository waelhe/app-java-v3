package com.marketplace.shared.observability;

import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class ModulithEventBusHealthIndicator extends AbstractHealthIndicator {

    private static final long STALE_THRESHOLD_SECONDS = 21600;

    private final JdbcTemplate jdbcTemplate;

    public ModulithEventBusHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        try {
            Integer staleCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL AND EXTRACT(EPOCH FROM (NOW() - publication_date)) > ?",
                    Integer.class,
                    STALE_THRESHOLD_SECONDS);
            if (staleCount != null && staleCount > 0) {
                builder.withDetail("staleEventCount", staleCount)
                       .withDetail("thresholdSeconds", STALE_THRESHOLD_SECONDS)
                       .down();
            } else {
                builder.up();
            }
        } catch (Exception e) {
            builder.down(e);
        }
    }
}

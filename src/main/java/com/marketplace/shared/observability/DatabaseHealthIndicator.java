package com.marketplace.shared.observability;

import javax.sql.DataSource;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;
import org.springframework.stereotype.Component;

import java.sql.Connection;

/**
 * Custom health indicator that verifies database connectivity
 * and reports connection pool details.
 * Uses Boot 4.0.5 health contributor packages.
 */
@Component
public class DatabaseHealthIndicator extends AbstractHealthIndicator {

    private final DataSource dataSource;

    public DatabaseHealthIndicator(DataSource dataSource) {
        super("Database health check failed");
        this.dataSource = dataSource;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            if (conn.isValid(2)) {
                builder.up()
                        .withDetail("database", "PostgreSQL")
                        .withDetail("validationQuery", "isValid(2s)");
            } else {
                builder.down().withDetail("error", "Connection validation failed");
            }
        }
    }
}
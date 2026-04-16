package com.marketplace.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically refreshes the {@code mv_listing_search} materialized view
 * so that full-text search results stay up-to-date.
 */
@Component
public class SearchIndexRefresher {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexRefresher.class);

    private final JdbcTemplate jdbcTemplate;

    public SearchIndexRefresher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Refresh the materialized view every 5 minutes.
     * {@code CONCURRENTLY} allows reads during refresh (requires unique index).
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    public void refreshMaterializedView() {
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_listing_search");
            log.debug("Refreshed mv_listing_search materialized view");
        } catch (Exception e) {
            log.warn("Failed to refresh mv_listing_search: {}", e.getMessage());
        }
    }
}
package com.marketplace.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.quartz.QuartzJobBean;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;

/**
 * Periodically refreshes the {@code mv_listing_search} materialized view
 * so that full-text search results stay up-to-date.
 */
@Component
@DisallowConcurrentExecution
public class SearchIndexRefresher extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexRefresher.class);

    private final JdbcTemplate jdbcTemplate;

    public SearchIndexRefresher(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) {
        try {
            jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_listing_search");
            log.debug("Refreshed mv_listing_search materialized view");
        } catch (Exception e) {
            log.warn("Failed to refresh mv_listing_search: {}", e.getMessage());
        }
    }
}

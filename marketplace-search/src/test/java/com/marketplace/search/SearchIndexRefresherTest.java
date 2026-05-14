package com.marketplace.search;

import org.junit.jupiter.api.Test;
import org.quartz.JobExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SearchIndexRefresherTest {

    @Test
    void executesRefreshMaterializedView() {
        var jdbcTemplate = mock(JdbcTemplate.class);
        var refresher = new SearchIndexRefresher(jdbcTemplate);
        var context = mock(JobExecutionContext.class);

        refresher.executeInternal(context);

        verify(jdbcTemplate).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_listing_search");
    }
}

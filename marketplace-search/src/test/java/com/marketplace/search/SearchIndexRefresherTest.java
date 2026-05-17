package com.marketplace.search;

import com.marketplace.shared.api.CatalogSearchPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchIndexRefresherTest {

    @Test
    void refreshesMaterializedView() {
        var jdbcTemplate = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        SearchIndexRefresher refresher = new SearchIndexRefresher(jdbcTemplate);

        refresher.executeInternal(null); // context is unused

        verify(jdbcTemplate).execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_listing_search");
    }
}

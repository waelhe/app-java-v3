package com.marketplace.search.spi;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — criterion 7): the search module's
 * implementation of the {@link AuthoredContentPurgePort} cross-module
 * contract. The saved search's only authored free text is the criteria's
 * {@code query} component — the purge REMOVES that JSON key
 * ({@code criteria - 'query'}), the JSON analog of the contract's
 * "nullable text becomes NULL" (absence is the record's own criterion-less
 * form for the text dimension), NOT the {@code [purged]} tombstone: a
 * tombstoned query would keep the saved search literally matching
 * listings whose text contains the word "purged". The rest of the
 * criteria (facets, window, radius) is structured, not authored text —
 * it stays so the saved search keeps its non-textual meaning for the
 * still-living (pseudonymized) account.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code jsonb_exists} filter — exact counts, zero
 * matches on re-run; no Envers revision for the purge itself (the
 * orchestrator's structured log line is the audit record). The
 * {@code ?} jsonb operator is deliberately spelled as the function
 * {@code jsonb_exists(criteria, 'query')} — the PostgreSQL JDBC driver
 * reserves the bare {@code ?} for parameter placeholders (the driver's
 * documented escaping rule).
 *
 * <p><b>The matches ledger is out of scope by design:</b> it carries
 * identifiers and timestamps only — no authored text, nothing to purge
 * (the accounting-row analogy in the b-3/b-5 discrimination).
 */
@Component
public class SearchContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(SearchContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public SearchContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int savedSearches = jdbcTemplate.update(
                "UPDATE saved_searches SET criteria = criteria - 'query' "
                        + "WHERE user_id = ? AND jsonb_exists(criteria, 'query')",
                userId);
        int auditRows = jdbcTemplate.update(
                "UPDATE saved_searches_aud SET criteria = criteria - 'query' "
                        + "WHERE user_id = ? AND jsonb_exists(criteria, 'query')",
                userId);
        log.info("Saved-search content purge: userId={}, savedSearches={}, auditRows={}",
                userId, savedSearches, auditRows);
        return savedSearches + auditRows;
    }
}

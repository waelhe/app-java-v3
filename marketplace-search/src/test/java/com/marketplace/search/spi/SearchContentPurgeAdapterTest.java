package com.marketplace.search.spi;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 12 (agent findings 2026-10-26 — CWE-362): the saved-search purge
 * must advance the live row's optimistic-lock {@code version} atomically
 * with the criteria erasure. The statement-shape contract under test:
 *
 * <ul>
 *   <li>the LIVE statement carries {@code version = version + 1} — an
 *       alert scan holding a pre-purge managed {@code SavedSearch} flushes
 *       zero rows and fails with the framework's optimistic-lock exception
 *       instead of restoring the erased query into the live row and a new
 *       audit revision;</li>
 *   <li>the AUDIT statement removes the {@code query} key WITHOUT touching
 *       any version column — {@code saved_searches_aud} rows are immutable
 *       history keyed by revision;</li>
 *   <li>the authorship predicate stays {@code user_id} and the
 *       idempotence filter stays {@code jsonb_exists(criteria, 'query')}
 *       (the port's contract — exact counts, zero matches on re-run).</li>
 * </ul>
 */
class SearchContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private SearchContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SearchContentPurgeAdapter(jdbcTemplate);
    }

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(contains("UPDATE saved_searches SET criteria"), eq(SUBJECT)))
                .thenReturn(2);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The optimistic-lock advance rides the SAME single statement as the
        // criteria erasure — atomic by construction.
        verify(jdbcTemplate).update(
                contains("UPDATE saved_searches SET criteria = criteria - 'query', version = version + 1"),
                eq(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_scrubsTheAuditMirrorWithoutTouchingAnyVersionColumn() {
        when(jdbcTemplate.update(contains("UPDATE saved_searches SET criteria"), eq(SUBJECT)))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE saved_searches_aud SET criteria"), eq(SUBJECT)))
                .thenReturn(3);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertEquals(3, purged);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE saved_searches_aud SET criteria = criteria - 'query'")
                                && !sql.contains("version")),
                eq(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_keepsTheUserIdPredicateAndTheIdempotenceFilter() {
        when(jdbcTemplate.update(contains("UPDATE saved_searches SET criteria"), eq(SUBJECT)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE saved_searches_aud SET criteria"), eq(SUBJECT)))
                .thenReturn(1);

        assertEquals(2, adapter.purgeAuthoredTexts(SUBJECT));

        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(
                contains("WHERE user_id = ? AND jsonb_exists(criteria, 'query')"),
                eq(SUBJECT));
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(any(String.class),
                eq(UUID.randomUUID()));
    }
}

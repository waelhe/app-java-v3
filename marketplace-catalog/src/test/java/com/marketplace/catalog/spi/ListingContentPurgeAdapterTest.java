package com.marketplace.catalog.spi;

import java.util.UUID;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * I7 gate b-3 (account-pseudonymization-plan §2) — the free-text purge
 * contract for the listings the subject authored, plus row 1 (agent
 * findings 2026-10-25): the purge's derived-cache eviction. Four
 * idempotent UPDATEs (the base table and its {@code provider_listings_aud}
 * mirror), the exact summed count, the authorship predicate
 * ({@code provider_id = ?}), and — when rows were actually purged — one
 * {@link CacheInvalidationRequested} event carrying
 * {@link CatalogService#CATALOG_CACHE_NAMES}, published inside the
 * adapter's transaction so the platform relay evicts the four cached
 * public summaries AFTER_COMMIT. An idempotent re-run (zero rows)
 * publishes nothing.
 */
class ListingContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private ListingContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ListingContentPurgeAdapter(jdbcTemplate, eventPublisher);
    }

    private void stubCounts(int titles, int descriptions, int titleAudits, int descriptionAudits) {
        when(jdbcTemplate.update(contains("UPDATE provider_listings SET title"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(titles);
        when(jdbcTemplate.update(contains("UPDATE provider_listings SET description"), eq(SUBJECT)))
                .thenReturn(descriptions);
        when(jdbcTemplate.update(contains("UPDATE provider_listings_aud SET title"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(titleAudits);
        when(jdbcTemplate.update(contains("UPDATE provider_listings_aud SET description"), eq(SUBJECT)))
                .thenReturn(descriptionAudits);
    }

    @Test
    void purgeAuthoredTexts_sumsEveryAffectedRow() {
        stubCounts(2, 3, 2, 3);

        assertEquals(10, adapter.purgeAuthoredTexts(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_purgesTitlesWithTheTombstoneAndDescriptionsWithNull() {
        stubCounts(0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        // title is varchar(200) NOT NULL — the marker is the honest
        // representation the constraint admits (the port contract).
        verify(jdbcTemplate).update(
                "UPDATE provider_listings SET title = ? WHERE provider_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
        // description is nullable text — NULL is the Phase 1 storage
        // convention.
        verify(jdbcTemplate).update(
                "UPDATE provider_listings SET description = NULL WHERE provider_id = ? "
                        + "AND description IS NOT NULL",
                SUBJECT);
    }

    @Test
    void purgeAuthoredTexts_mirrorsThePurgeIntoTheAuditTable() {
        stubCounts(0, 0, 0, 0);

        adapter.purgeAuthoredTexts(SUBJECT);

        verify(jdbcTemplate).update(
                "UPDATE provider_listings_aud SET title = ? WHERE provider_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, SUBJECT, AuthoredContentPurgePort.PURGED_MARKER);
        verify(jdbcTemplate).update(
                "UPDATE provider_listings_aud SET description = NULL WHERE provider_id = ? "
                        + "AND description IS NOT NULL",
                SUBJECT);
    }

    @Test
    void purgeAuthoredTexts_requestsCacheInvalidationWhenRowsWerePurged() {
        stubCounts(2, 3, 2, 3);

        adapter.purgeAuthoredTexts(SUBJECT);

        // row 1: the derived-cache eviction — one event, the module's
        // shared cache-name set (the four cached public summaries),
        // published inside the adapter's transaction for the AFTER_COMMIT
        // relay.
        verify(eventPublisher).publishEvent(
                new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
    }

    @Test
    void purgeAuthoredTexts_isIdempotentAndPublishesNothingOnTheZeroRowReRun() {
        stubCounts(0, 0, 0, 0);

        assertEquals(0, adapter.purgeAuthoredTexts(SUBJECT));
        assertEquals(0, adapter.purgeAuthoredTexts(SUBJECT));

        // the idempotent re-run changed nothing — no cache clear is owed
        // (the expiry executor's !isEmpty() discipline).
        verify(eventPublisher, never()).publishEvent(any());
    }
}

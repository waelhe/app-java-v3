package com.marketplace.catalog.spi;

import java.util.UUID;

import com.marketplace.catalog.CatalogService;
import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 (account-pseudonymization-plan §9 row "نصوص اللوحات التجارية" — resolved
 * option 1, the eighth converter): the catalog module's implementation of the
 * {@link AuthoredContentPurgePort} contract. Purges the listing texts the
 * subject authored as a provider on {@code provider_listings} — the inventory
 * row measured in the row itself: {@code provider_id uuid not null references
 * users(id)} (V2:4), the write path stores the caller's user id
 * ({@code CatalogController.create} via {@code getCurrentUserId}), so the
 * titles and descriptions are texts a specific identifiable user authored —
 * the GDPR personal-data reach the row's gate was opened for.
 *
 * <p><b>Schema facts (measured, V2/V24):</b> {@code description} is nullable
 * {@code text} — the purge writes NULL (the Phase 1 storage convention);
 * {@code title} is {@code varchar(200) NOT NULL} — the purge writes the
 * shared {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone (the honest
 * representation the constraint admits). {@code provider_listings_aud}
 * mirrors {@code provider_id}, {@code title} and {@code description} (V24
 * §2), so the mirror purges with the same predicates — a purge that left the
 * mirror intact would be cosmetic (the original texts survive in the audit
 * trail, a silent debt).
 *
 * <p><b>What stays (the row's own scope, the shared-record basis):</b> the
 * listing row itself and its operational columns (category, price, currency,
 * status, capacity) — reference integrity for the bookings that reference it
 * and the counterparty's transaction history (Art. 17(3)(b) / 20(4), the
 * plan's §4 refusal of hard deletes) keep the record alive in purged form.
 * The search indexes need no separate handling: V29 removed the materialized
 * view (the never-read copy), and both surviving indexes (V9 FTS / V34 trgm)
 * are expressions over {@code provider_listings.title/description} itself —
 * they index the table's own columns, so the purge's UPDATE maintains them
 * with it (no denormalized text survives anywhere).
 *
 * <p><b>Statement shape (the port's contract, the six adapters' house
 * pattern):</b> native JDBC UPDATE, idempotent by the {@code IS NOT NULL} /
 * {@code <> marker} filters — exact counts, zero matches on re-run; no
 * Envers revision for the purge itself (the orchestrator's structured log
 * line is the audit record).
 *
 * <p><b>row 1 (agent findings 2026-10-25 — the derived-cache eviction the
 * purge owes its contract):</b> the §9 listings row measured that the
 * surviving search indexes are expressions over the table's own columns
 * (V9 FTS / V34 trgm — they follow the UPDATE), but that measurement
 * stopped at the database boundary: the four <em>cached</em> public
 * summaries ({@code CatalogService.CATALOG_CACHE_NAMES} — catalog-active-v4,
 * catalog-by-category-v4, catalog-search-v4, search-results-v6) hold
 * <b>copies</b> of the erased title, and a warm cache hit served the
 * original text after the purge reported success — up to the 1h TTL, a
 * live contradiction of the purge contract's replacement-marker
 * behavior. The fix reuses the module's existing shared invalidation
 * mechanism verbatim (CodeRabbit's own recommendation): when rows were
 * actually purged, this adapter publishes {@link CacheInvalidationRequested}
 * <em>inside this method's transaction</em>, and the platform relay
 * ({@code CacheInvalidationRelay}) evicts the four caches
 * <em>AFTER_COMMIT</em> — the official Spring transaction-bound event
 * phase (Spring Framework Reference, Transaction-bound Events: "the
 * listener is bound to the commit phase of the transaction by default"),
 * the exact shape every catalog write path and the L33 expiry batch
 * executor already ride. An idempotent re-run (zero rows) publishes
 * nothing: the database is unchanged, so the caches hold no new stale
 * copy (the zero-row re-run stays free of needless full-cache clears —
 * the expiry executor's {@code !isEmpty()} discipline).
 */
@Component
public class ListingContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ListingContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ListingContentPurgeAdapter(JdbcTemplate jdbcTemplate,
                                      ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int descriptions = jdbcTemplate.update(
                "UPDATE provider_listings SET description = NULL WHERE provider_id = ? "
                        + "AND description IS NOT NULL",
                userId);
        int titles = jdbcTemplate.update(
                "UPDATE provider_listings SET title = ? WHERE provider_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int descriptionAudits = jdbcTemplate.update(
                "UPDATE provider_listings_aud SET description = NULL WHERE provider_id = ? "
                        + "AND description IS NOT NULL",
                userId);
        int titleAudits = jdbcTemplate.update(
                "UPDATE provider_listings_aud SET title = ? WHERE provider_id = ? "
                        + "AND title IS NOT NULL AND title <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int total = titles + descriptions + titleAudits + descriptionAudits;
        log.info("Listing content purge: userId={}, titles={}, descriptions={}, "
                        + "auditRows={}",
                userId, titles, descriptions, titleAudits + descriptionAudits);
        if (total > 0) {
            // AFTER_COMMIT relay: published inside THIS adapter's transaction,
            // evicted at its commit — the erased text leaves the cached public
            // summaries (the four CATALOG_CACHE_NAMES caches) with the same
            // durability point as every catalog write path.
            eventPublisher.publishEvent(
                    new CacheInvalidationRequested(CatalogService.CATALOG_CACHE_NAMES));
        }
        return total;
    }
}

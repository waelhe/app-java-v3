package com.marketplace.catalog.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 */
@Component
public class ListingContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ListingContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public ListingContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        log.info("Listing content purge: userId={}, titles={}, descriptions={}, "
                        + "auditRows={}",
                userId, titles, descriptions, titleAudits + descriptionAudits);
        return titles + descriptions + titleAudits + descriptionAudits;
    }
}

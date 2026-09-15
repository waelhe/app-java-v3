package com.marketplace.provider.spi;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the provider module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * subject's provider-persona public texts on {@code provider_profiles}
 * (the plan's P14: "{@code user_id} (V22) + {@code display_name}/{@code
 * bio}" — and, since L36, the persona fields {@code agency_name}/
 * {@code license_number}, the same authored-text class) — the profile row
 * itself and its operational columns (status, rating statistics) stay: the
 * persona's record is the counterparty-facing surface whose structure the
 * plan's §4 keeps.
 *
 * <p><b>Schema facts (measured, V14/V22/V56):</b> {@code bio},
 * {@code agency_name} and {@code license_number} are nullable — the purge
 * writes NULL (the Phase 1 storage convention); {@code display_name} is
 * {@code varchar(200) NOT NULL} — the purge writes the shared
 * {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone (the honest
 * representation the constraint admits). {@code provider_profiles_aud}
 * mirrors all of them (V24 §11 + the V56 ALTER), so the mirror purges with
 * the same predicates.
 *
 * <p><b>Cache invalidation (CodeRabbit PR #318 round 1, adopted from the
 * root):</b> the purge runs as raw JDBC inside the orchestrator's
 * transaction — no entity write, so no service-level
 * {@code CacheInvalidationRequested} fires — and the {@code "providers"}
 * cache ({@code ProviderService.getById}, keyed by profile id) would keep
 * serving the erased persona. This adapter therefore resolves the
 * subject's profile id(s) and publishes the TARGETED invalidation through
 * the house mechanism (the AFTER_COMMIT relay — the event fires only after
 * the purge transaction commits, so the eviction cannot race a rollback).
 * The review's alternative branch — "clear the whole cache when no profile
 * id resolves" — is deliberately NOT taken, with the measured fact as the
 * documented reason: {@code provider_profiles} rows are never deleted (the
 * persona purge keeps the row by design; no hard-delete path exists), so a
 * cached entry can only exist for a resolvable row, and a zero-resolution
 * purge (a consumer-only account) would needlessly wipe the whole cache on
 * every consumer erasure. Targeted evictions are idempotent — the
 * already-purged re-run simply re-evicts the same key harmlessly.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL} / {@code <> marker} filters —
 * exact counts, zero matches on re-run; no Envers revision for the purge
 * itself (the orchestrator's structured log line is the audit record).
 */
@Component
public class ProviderProfileContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ProviderProfileContentPurgeAdapter.class);

    private static final Set<String> PROVIDER_CACHE_NAMES = Set.of("providers");

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public ProviderProfileContentPurgeAdapter(JdbcTemplate jdbcTemplate,
                                              ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int bios = jdbcTemplate.update(
                "UPDATE provider_profiles SET bio = NULL WHERE user_id = ? AND bio IS NOT NULL",
                userId);
        int names = jdbcTemplate.update(
                "UPDATE provider_profiles SET display_name = ? WHERE user_id = ? "
                        + "AND display_name IS NOT NULL AND display_name <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        // L36: the persona display fields are the same authored-text class
        // as bio (nullable public persona texts) — the existing masking
        // applies to them verbatim (the plan's acceptance criterion 3:
        // "التمويه القائم يسري"). NULL writes, the bio storage convention.
        int personas = jdbcTemplate.update(
                "UPDATE provider_profiles SET agency_name = NULL, license_number = NULL "
                        + "WHERE user_id = ? AND (agency_name IS NOT NULL OR license_number IS NOT NULL)",
                userId);
        int auditRows = jdbcTemplate.update(
                "UPDATE provider_profiles_aud SET bio = NULL WHERE user_id = ? AND bio IS NOT NULL",
                userId);
        int nameAudits = jdbcTemplate.update(
                "UPDATE provider_profiles_aud SET display_name = ? WHERE user_id = ? "
                        + "AND display_name IS NOT NULL AND display_name <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int personaAudits = jdbcTemplate.update(
                "UPDATE provider_profiles_aud SET agency_name = NULL, license_number = NULL "
                        + "WHERE user_id = ? AND (agency_name IS NOT NULL OR license_number IS NOT NULL)",
                userId);

        // The targeted cache invalidation (see the class javadoc): every
        // profile id the subject owns gets its "providers" entry evicted
        // AFTER this transaction commits — the erased persona is never
        // served from the cache.
        List<UUID> profileIds = jdbcTemplate.queryForList(
                "SELECT id FROM provider_profiles WHERE user_id = ?", UUID.class, userId);
        for (UUID profileId : profileIds) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, profileId));
        }

        log.info("Provider profile content purge: userId={}, bios={}, names={}, personas={}, auditRows={}, cacheEvictions={}",
                userId, bios, names, personas, auditRows + nameAudits + personaAudits, profileIds.size());
        return bios + names + personas + auditRows + nameAudits + personaAudits;
    }
}

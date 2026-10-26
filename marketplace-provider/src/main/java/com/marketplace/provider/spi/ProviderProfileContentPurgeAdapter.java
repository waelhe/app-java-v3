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
        // Row 31 (agent findings 2026-10-26, CWE-212): the offered-service
        // text joins the purge. The public page's services block is the
        // provider's own display statement — the SAME authored-text class
        // as the bio, attributed to the OWNING PROFILE (provider_profiles.
        // user_id) exactly as the page renders it: the delegation seam
        // (a team member typing on the owner's page) writes the owner's
        // content, so the reliable authorship attribution is the profile,
        // never the typist. The required title takes the tombstone marker
        // (V96 NOT NULL); the nullable description clears to NULL (the
        // bio's own storage convention) — the operational fields
        // (duration, price, position) stay so the page's structure
        // survives the erasure. Base table and Envers mirror both.
        // Row 4 (agent findings 2026-10-26 14:33 export, CWE-362): the text
        // erasure ADVANCES the live row's optimistic-lock version in the
        // SAME statement — a delegated menu writer holding a pre-erasure
        // entity snapshot (title/description loaded before the purge) fails
        // its flush on the version the erasure just advanced, instead of
        // writing the erased text back through the passed check (the
        // booking/disputes/search purge adapters' own "version = version +
        // 1" family). The audit mirror carries no version column (Envers
        // revisions) — the Envers twin below stays as-is.
        int serviceTitles = jdbcTemplate.update(
                "UPDATE provider_services s SET title = ?, description = NULL, "
                        + "version = version + 1 "
                        + "FROM provider_profiles p "
                        + "WHERE s.provider_id = p.id AND p.user_id = ? "
                        + "AND (s.title <> ? OR s.description IS NOT NULL)",
                AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER);
        int serviceTitleAudits = jdbcTemplate.update(
                "UPDATE provider_services_aud s SET title = ?, description = NULL "
                        + "FROM provider_profiles p "
                        + "WHERE s.provider_id = p.id AND p.user_id = ? "
                        + "AND (s.title <> ? OR s.description IS NOT NULL)",
                AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER);

        // The targeted cache invalidation (see the class javadoc): every
        // profile id the subject owns gets its "providers" entry evicted
        // AFTER this transaction commits — the erased persona is never
        // served from the cache.
        List<UUID> profileIds = jdbcTemplate.queryForList(
                "SELECT id FROM provider_profiles WHERE user_id = ?", UUID.class, userId);
        for (UUID profileId : profileIds) {
            eventPublisher.publishEvent(new CacheInvalidationRequested(PROVIDER_CACHE_NAMES, profileId));
        }

        log.info("Provider profile content purge: userId={}, bios={}, names={}, personas={}, serviceTitles={}, auditRows={}, cacheEvictions={}",
                userId, bios, names, personas, serviceTitles,
                auditRows + nameAudits + personaAudits + serviceTitleAudits, profileIds.size());
        return bios + names + personas + auditRows + nameAudits + personaAudits
                + serviceTitles + serviceTitleAudits;
    }
}

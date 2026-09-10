package com.marketplace.provider.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the provider module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * subject's provider-persona public texts on {@code provider_profiles}
 * (the plan's P14: "{@code user_id} (V22) + {@code display_name}/{@code
 * bio}") — the profile row itself and its operational columns (status,
 * rating statistics) stay: the persona's record is the counterparty-facing
 * surface whose structure the plan's §4 keeps.
 *
 * <p><b>Schema facts (measured, V14/V22):</b> {@code bio} is nullable
 * {@code varchar(1000)} — the purge writes NULL (the Phase 1 storage
 * convention); {@code display_name} is {@code varchar(200) NOT NULL} —
 * the purge writes the shared
 * {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone (the honest
 * representation the constraint admits). {@code provider_profiles_aud}
 * mirrors {@code user_id}, {@code display_name} and {@code bio}
 * (V24 §11), so the mirror purges with the same predicates.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL} / {@code <> marker} filters —
 * exact counts, zero matches on re-run; no Envers revision for the purge
 * itself (the orchestrator's structured log line is the audit record).
 */
@Component
public class ProviderProfileContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(ProviderProfileContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public ProviderProfileContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        int auditRows = jdbcTemplate.update(
                "UPDATE provider_profiles_aud SET bio = NULL WHERE user_id = ? AND bio IS NOT NULL",
                userId);
        int nameAudits = jdbcTemplate.update(
                "UPDATE provider_profiles_aud SET display_name = ? WHERE user_id = ? "
                        + "AND display_name IS NOT NULL AND display_name <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Provider profile content purge: userId={}, bios={}, names={}, auditRows={}",
                userId, bios, names, auditRows + nameAudits);
        return bios + names + auditRows + nameAudits;
    }
}

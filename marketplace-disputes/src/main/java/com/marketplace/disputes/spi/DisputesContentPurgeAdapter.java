package com.marketplace.disputes.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the disputes module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * {@code reason} of the disputes the subject <em>opened</em> (the plan's
 * P15: "{@code opened_by} + {@code reason}") — the dispute row, its status
 * and its financial resolution columns stay (Art. 17(3)(b) / 20(4) — the
 * plan's §4 keeps the shared record; the resolution text is the admin's
 * authored record, not the subject's, and never matches the predicate).
 *
 * <p><b>Schema facts (measured, V20):</b> {@code disputes.reason} is
 * {@code varchar(1000) NOT NULL} — the nullable-NULL convention is
 * impossible here, so the purge writes the shared
 * {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone.
 * {@code disputes_aud} mirrors {@code opened_by} and {@code reason}
 * (V24 §18), so the mirror purges with the same predicate.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL AND <> marker} filter — exact
 * counts, zero matches on re-run; no Envers revision for the purge itself
 * (the orchestrator's structured log line is the audit record).
 */
@Component
public class DisputesContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(DisputesContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public DisputesContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int disputes = jdbcTemplate.update(
                "UPDATE disputes SET reason = ? WHERE opened_by = ? AND reason IS NOT NULL AND reason <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int auditRows = jdbcTemplate.update(
                "UPDATE disputes_aud SET reason = ? WHERE opened_by = ? AND reason IS NOT NULL AND reason <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Disputes content purge: userId={}, disputes={}, auditRows={}", userId, disputes, auditRows);
        return disputes + auditRows;
    }
}

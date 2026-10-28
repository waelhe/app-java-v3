package com.marketplace.knowledge.spi;

import java.util.List;
import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import com.marketplace.shared.api.KnowledgeEntryWithdrawnEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Row 36 (CodeRabbit agent findings 2026-10-26 — CWE-212's knowledge leg):
 * the knowledge module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. The entry's
 * title and body are the subject's authored free text (both NOT NULL,
 * V156, so the shared {@link AuthoredContentPurgePort#PURGED_MARKER}
 * tombstone is the honest representation) — the base table AND the
 * Envers mirror purge with the same predicate (the port's contract: a
 * purge that left the history intact would be cosmetic). The structural
 * columns (category, location — the guide's own vocabulary and geo
 * anchor) stay: the guide keeps its shape, the text dies.
 *
 * <p><b>The downstream leg — the withdrawal integration (the finding's
 * own instruction):</b> the AI module serves every published entry from
 * its knowledge index; an erasure that left the indexed copy alive would
 * keep answering the subject's text through discovery. Every entry whose
 * text still carries content therefore rides the SAME withdrawal signal
 * the author's own withdraw publishes —
 * {@link KnowledgeEntryWithdrawnEvent} through the Modulith registry
 * (AFTER_COMMIT, retried until consumed): the listener marks the source
 * withdrawn in the durable record, the guarded retrieval drops it, and a
 * late replayed publication cannot re-expose it (the withdrawal record's
 * own documented idempotence). The event fires ONLY for entries the
 * purge actually touched — a re-run matches zero rows and publishes
 * nothing.</p>
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code <> marker} filter — exact counts, zero
 * matches on re-run; no is_deleted filter anywhere (native SQL bypasses
 * the soft-delete filter: a withdrawn entry's text purges exactly like a
 * live one's — the "including soft-deleted rows" requirement); no Envers
 * revision for the purge itself (the orchestrator's structured log line
 * is the audit record).</p>
 */
@Component
public class KnowledgeContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;

    public KnowledgeContentPurgeAdapter(JdbcTemplate jdbcTemplate,
                                        ApplicationEventPublisher eventPublisher) {
        this.jdbcTemplate = jdbcTemplate;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        // The withdrawal ride-along resolves FIRST — the ids and locations
        // of the entries whose text still carries content, so the event
        // fires exactly once per actually-purged entry (a re-run reads an
        // empty list and publishes nothing).
        List<KnowledgeWithdrawal> withdrawals = jdbcTemplate.query(
                "SELECT id, location_id FROM knowledge_entries "
                        + "WHERE author_id = ? AND (title <> ? OR body <> ?)",
                (rs, rowNum) -> new KnowledgeWithdrawal(
                        rs.getObject("id", UUID.class),
                        rs.getObject("location_id", UUID.class)),
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int entries = jdbcTemplate.update(
                // version = version + 1 (review round 1): KnowledgeEntry
                // inherits @Version from BaseEntity, so a JDBC update that
                // leaves the version untouched can be silently reverted by
                // a stale managed entity's flush (the optimistic-lock check
                // never sees the purge). The audit table carries no version
                // column and stays as-is.
                "UPDATE knowledge_entries SET title = ?, body = ?, version = version + 1 "
                        + "WHERE author_id = ? AND (title <> ? OR body <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int auditRows = jdbcTemplate.update(
                "UPDATE knowledge_entries_aud SET title = ?, body = ? "
                        + "WHERE author_id = ? AND (title <> ? OR body <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        for (KnowledgeWithdrawal withdrawal : withdrawals) {
            eventPublisher.publishEvent(new KnowledgeEntryWithdrawnEvent(
                    withdrawal.entryId(), withdrawal.locationId()));
        }
        log.info("Knowledge content purge: userId={}, entries={}, auditRows={}, withdrawals={}",
                userId, entries, auditRows, withdrawals.size());
        return entries + auditRows;
    }

    /** One purged entry's identity for the withdrawal signal. */
    private record KnowledgeWithdrawal(UUID entryId, UUID locationId) {
    }
}

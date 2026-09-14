package com.marketplace.messaging.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the messaging module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * {@code content} of the messages the subject <em>sent</em> (the plan's
 * provenance rule — "نصوص مرسلها") on the base table and the Envers
 * mirror; the conversation rows carry no free text (V7: participant UUIDs
 * and the booking reference only), and the counterparty's messages never
 * match {@code sender_id = ?}.
 *
 * <p><b>L34 (realestate systems plan §5 — lead capture):</b> the leads the
 * subject submitted while authenticated join the purge scope — the plan's
 * acceptance criterion 5 ("بيانات الاتصال تعرض التمويه القائم عند تطهير
 * حساب — الـlead نص مؤلَّف للمُرسل"). {@code contact_name},
 * {@code contact_phone} and {@code message} are NOT NULL (V52), so the
 * shared {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone is the
 * honest representation; guest-submitted leads (sender_user_id NULL)
 * belong to no account and are never matched — nothing of an anonymous
 * submitter can be tied to an erasure subject. The IP fingerprint column
 * is not free text and not person-identifying (a one-way hash) — it stays
 * so the G-R6 daily cap keeps bounding the purged account's historic
 * fingerprint.
 *
 * <p><b>Schema facts (measured, V7):</b> {@code messages.content} is
 * {@code text NOT NULL} — the nullable-NULL convention is impossible here,
 * so the purge writes the shared {@link AuthoredContentPurgePort#PURGED_MARKER}
 * tombstone. {@code messages_aud} mirrors {@code sender_id} and
 * {@code content} (V24 §9), so the mirror purges with the same predicate.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE —
 * the Envers mirror has no mapped entity, and the base statement shares
 * the same deterministic channel; no revision is written for the purge
 * itself (the orchestrator's structured log line is the audit record).
 * The {@code content IS NOT NULL AND content <> ?} filter makes the
 * statement idempotent with exact counts: already-purged rows (the
 * marker) and never-had-content rows (NULL — Envers DEL revisions) match
 * nothing.
 */
@Component
public class MessagingContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(MessagingContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public MessagingContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int messages = jdbcTemplate.update(
                "UPDATE messages SET content = ? WHERE sender_id = ? AND content IS NOT NULL AND content <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int auditRows = jdbcTemplate.update(
                "UPDATE messages_aud SET content = ? WHERE sender_id = ? AND content IS NOT NULL AND content <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        // L34: the leads this subject submitted while authenticated — all
        // three text columns carry his contact data, base and mirror.
        int leads = jdbcTemplate.update(
                "UPDATE listing_leads SET contact_name = ?, contact_phone = ?, message = ? "
                        + "WHERE sender_user_id = ? AND message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int leadAuditRows = jdbcTemplate.update(
                "UPDATE listing_leads_aud SET contact_name = ?, contact_phone = ?, message = ? "
                        + "WHERE sender_user_id = ? AND message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Messaging content purge: userId={}, messages={}, auditRows={}, leads={}, leadAuditRows={}",
                userId, messages, auditRows, leads, leadAuditRows);
        return messages + auditRows + leads + leadAuditRows;
    }
}

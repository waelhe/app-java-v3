package com.marketplace.notifications.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the notifications module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * {@code message} bodies of the subject's own notification feed (the
 * plan's P12 rides gate b-3 by the §6 matrix row "P9-P16 المرجعيات
 * والنصوص"). The feed is recipient-scoped by table design (V18 — the
 * plan's own measurement: "الجدول محصور بالمستلم أصلاً"), so
 * {@code recipient_id = ?} is exactly the subject's rows; the
 * notification rows themselves, their types and read state stay (delivery
 * state is operational data, not text).
 *
 * <p><b>Schema facts (measured, V18):</b> {@code notifications.message}
 * is {@code varchar(500) NOT NULL} — the purge writes the shared
 * {@link AuthoredContentPurgePort#PURGED_MARKER} tombstone (13 chars, far
 * inside the constraint). {@code notifications_aud} mirrors
 * {@code recipient_id} and {@code message} (V24 §15), so the mirror
 * purges with the same predicate.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code IS NOT NULL AND <> marker} filter — exact
 * counts, zero matches on re-run; no Envers revision for the purge itself
 * (the orchestrator's structured log line is the audit record).
 */
@Component
public class NotificationsContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(NotificationsContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public NotificationsContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int notifications = jdbcTemplate.update(
                "UPDATE notifications SET message = ? WHERE recipient_id = ? "
                        + "AND message IS NOT NULL AND message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        int auditRows = jdbcTemplate.update(
                "UPDATE notifications_aud SET message = ? WHERE recipient_id = ? "
                        + "AND message IS NOT NULL AND message <> ?",
                AuthoredContentPurgePort.PURGED_MARKER, userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Notifications content purge: userId={}, notifications={}, auditRows={}",
                userId, notifications, auditRows);
        return notifications + auditRows;
    }
}

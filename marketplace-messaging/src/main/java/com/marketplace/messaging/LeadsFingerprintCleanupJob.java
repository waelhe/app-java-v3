package com.marketplace.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * L34 (realestate systems plan §5 — lead capture, CodeRabbit round-1
 * adoption, finding 4): the sender IP fingerprint exists for exactly one
 * purpose — the G-R6 rolling 24h daily-cap count — so it is retained for
 * exactly that window (plus an hour's margin) and no longer. The nightly
 * sweep nulls the fingerprint on rows whose window has closed, base table
 * and Envers mirror alike (data minimization by design: even a keyed
 * pseudonym is not kept past its purpose).
 *
 * <p><b>The house maintenance-statement shape</b> (the purge-adapter and
 * {@code ListingExpiryJob} precedents): native JDBC UPDATEs bypass entity
 * dirty-checking — no Envers revision is written for the sweep itself,
 * and its audit record is this class's structured log line (the counts it
 * reports). The 25h margin keeps every live window intact — the cap's
 * count query only ever reads rows younger than 24h, so a hash the sweep
 * has not reached yet is by definition outside any live window.
 *
 * <p>Idempotent by predicate: a re-run matches only rows still carrying a
 * fingerprint inside the expired range.
 */
@Component
public class LeadsFingerprintCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(LeadsFingerprintCleanupJob.class);

    /** One hour past the 24h window — the safety margin. */
    static final String EXPIRED_WINDOW = "25 hours";

    private final JdbcTemplate jdbcTemplate;

    public LeadsFingerprintCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Nightly at 03:30 (the {@code EventPublicationCleanup} 3 AM family,
     * offset to avoid the same minute). Env-tunable through the standard
     * relaxed binding ({@code MARKETPLACE_MESSAGING_LEADS_FINGERPRINT_CLEANUP_CRON}).
     */
    @Scheduled(cron = "${marketplace.messaging.leads.fingerprint-cleanup-cron:0 30 3 * * *}")
    @Transactional
    public void expireClosedWindowFingerprints() {
        int base = jdbcTemplate.update(
                "UPDATE listing_leads SET sender_ip_hash = NULL "
                        + "WHERE sender_ip_hash IS NOT NULL AND created_at < now() - interval '"
                        + EXPIRED_WINDOW + "'");
        int mirror = jdbcTemplate.update(
                "UPDATE listing_leads_aud SET sender_ip_hash = NULL "
                        + "WHERE sender_ip_hash IS NOT NULL AND created_at < now() - interval '"
                        + EXPIRED_WINDOW + "'");
        log.info("Lead fingerprint sweep: expired {} base rows and {} audit rows (window {})",
                base, mirror, EXPIRED_WINDOW);
    }
}

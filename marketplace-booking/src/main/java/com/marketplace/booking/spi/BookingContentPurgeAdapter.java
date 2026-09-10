package com.marketplace.booking.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 — the free-text
 * purge): the booking module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract. Purges the
 * {@code notes} of the bookings the subject <em>requested</em> — the plan
 * names this column explicitly ("{@code bookings.notes}") and the write
 * path is measured: {@code BookingService.create(consumerId, …, notes)}
 * is the only writer of the column, so {@code consumer_id = ?} is exactly
 * the subject's authored set. The booking row itself, its status history
 * and its financial columns stay (Art. 17(3)(b) / 20(4) — the plan's §4).
 *
 * <p><b>Schema facts (measured, V3):</b> {@code bookings.notes} is
 * nullable {@code text} — the purge writes NULL (the Phase 1 storage
 * convention). {@code bookings_aud} mirrors {@code consumer_id} and
 * {@code notes} (V24 §3), so the mirror purges with the same predicate.
 *
 * <p><b>Statement shape (the port's contract):</b> native JDBC UPDATE,
 * idempotent by the {@code notes IS NOT NULL} filter — exact counts, zero
 * matches on re-run; no Envers revision for the purge itself (the
 * orchestrator's structured log line is the audit record).
 */
@Component
public class BookingContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(BookingContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public BookingContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int bookings = jdbcTemplate.update(
                "UPDATE bookings SET notes = NULL WHERE consumer_id = ? AND notes IS NOT NULL",
                userId);
        int auditRows = jdbcTemplate.update(
                "UPDATE bookings_aud SET notes = NULL WHERE consumer_id = ? AND notes IS NOT NULL",
                userId);
        log.info("Booking content purge: userId={}, bookings={}, auditRows={}", userId, bookings, auditRows);
        return bookings + auditRows;
    }
}

package com.marketplace.booking.spi;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 29 (agent findings 2026-10-26 — CWE-362): the booking-notes purge
 * must advance the live row's optimistic-lock {@code version} atomically
 * with the erasure. The statement-shape contract under test:
 *
 * <ul>
 *   <li>the LIVE statement carries {@code version = version + 1} — a
 *       lifecycle transaction holding a pre-purge booking snapshot flushes
 *       zero rows and fails with the framework's optimistic-lock exception
 *       instead of restoring the erased notes into the live row and a new
 *       audit revision;</li>
 *   <li>the AUDIT statement clears the mirrored notes WITHOUT touching any
 *       version column — {@code bookings_aud} rows are immutable history
 *       keyed by revision;</li>
 *   <li>the authorship predicate stays {@code consumer_id} (the only
 *       measured writer of {@code notes} is the consumer's own booking
 *       creation) and the idempotence filter stays
 *       {@code notes IS NOT NULL} (the port's contract).</li>
 * </ul>
 */
class BookingContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private BookingContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BookingContentPurgeAdapter(jdbcTemplate);
    }

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(contains("UPDATE bookings SET notes"), eq(SUBJECT)))
                .thenReturn(2);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The optimistic-lock advance rides the SAME single statement as the
        // notes erasure — atomic by construction.
        verify(jdbcTemplate).update(
                contains("UPDATE bookings SET notes = NULL, version = version + 1"),
                eq(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_clearsTheAuditMirrorWithoutTouchingAnyVersionColumn() {
        when(jdbcTemplate.update(contains("UPDATE bookings SET notes"), eq(SUBJECT)))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE bookings_aud SET notes"), eq(SUBJECT)))
                .thenReturn(3);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertEquals(3, purged);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE bookings_aud SET notes = NULL")
                                && !sql.contains("version")),
                eq(SUBJECT));
    }

    @Test
    void purgeAuthoredTexts_keepsTheConsumerPredicateAndTheIdempotenceFilter() {
        when(jdbcTemplate.update(contains("UPDATE bookings SET notes"), eq(SUBJECT)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE bookings_aud SET notes"), eq(SUBJECT)))
                .thenReturn(1);

        assertEquals(2, adapter.purgeAuthoredTexts(SUBJECT));

        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(
                contains("WHERE consumer_id = ? AND notes IS NOT NULL"),
                eq(SUBJECT));
    }
}

package com.marketplace.disputes.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Row 3 (agent findings 2026-10-26 — CWE-362): the disputes purge must
 * advance the live row's optimistic-lock {@code version} atomically with
 * the text erasure. The statement-shape contract under test:
 *
 * <ul>
 *   <li>the LIVE statement carries {@code version = version + 1} — a
 *       resolution holding a pre-purge managed entity flushes zero rows
 *       and fails with the framework's optimistic-lock exception instead
 *       of restoring the erased reason into the live row and a new Envers
 *       revision;</li>
 *   <li>the AUDIT statement purges the mirrored text WITHOUT touching any
 *       version column — {@code disputes_aud} rows are immutable history
 *       keyed by revision, there is no optimistic-lock semantics to
 *       advance there;</li>
 *   <li>the authorship predicate stays {@code opened_by} and the
 *       idempotence filter stays {@code IS NOT NULL AND <> marker} (the
 *       port's contract — exact counts, zero matches on re-run).</li>
 * </ul>
 */
class DisputesContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

    private DisputesContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DisputesContentPurgeAdapter(jdbcTemplate);
    }

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(contains("UPDATE disputes SET reason"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(2);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The optimistic-lock advance rides the SAME single statement as the
        // text erasure — atomic by construction, never a separate UPDATE a
        // concurrent writer could slip between.
        verify(jdbcTemplate).update(
                contains("UPDATE disputes SET reason = ?, version = version + 1"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    @Test
    void purgeAuthoredTexts_scrubsTheAuditMirrorWithoutTouchingAnyVersionColumn() {
        when(jdbcTemplate.update(contains("UPDATE disputes SET reason"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(0);
        when(jdbcTemplate.update(contains("UPDATE disputes_aud SET reason"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(3);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertEquals(3, purged);
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE disputes_aud SET reason = ?")
                                && !sql.contains("version")),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }

    @Test
    void purgeAuthoredTexts_keepsTheOpenedByPredicateAndTheIdempotenceFilter() {
        when(jdbcTemplate.update(contains("UPDATE disputes SET reason"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(1);
        when(jdbcTemplate.update(contains("UPDATE disputes_aud SET reason"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER)))
                .thenReturn(1);

        assertEquals(2, adapter.purgeAuthoredTexts(SUBJECT));

        // CI round 4 (measured on b21470db): BOTH statements — the live row
        // AND the audit mirror — carry the SAME authorship predicate and
        // idempotence filter (the mirror purge joined the adapter with the
        // round-1 purge family); the single-invocation verify of the
        // pre-mirror era answered TooManyActualInvocations. The port's
        // contract is asserted on EACH of the two statements.
        verify(jdbcTemplate, times(2)).update(
                contains("WHERE opened_by = ? AND reason IS NOT NULL AND reason <> ?"),
                eq(AuthoredContentPurgePort.PURGED_MARKER), eq(SUBJECT),
                eq(AuthoredContentPurgePort.PURGED_MARKER));
    }
}

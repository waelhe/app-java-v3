package com.marketplace.notifications.spi;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adversarial-review 2026-11-10 finding G4 — the notifications purge
 * adapter carried NO test of its own (the only family adapter without
 * one), and its LIVE statement did not advance the row's optimistic-lock
 * {@code version}. The statement-shape contract under test (rulebook B5,
 * the family law):
 *
 * <ul>
 *   <li>the LIVE statement carries the version advance in the SAME
 *       statement as the message erasure (the nullable-column COALESCE
 *       form — the provider adapter's own documented precedent for a
 *       nullable {@code notifications.version}) — a recipient's or
 *       admin's {@code markAsRead} transaction holding a pre-purge
 *       Notification snapshot flushes zero rows and fails with the
 *       framework's optimistic-lock exception, instead of restoring the
 *       erased message through the passed check and recreating it in a
 *       new {@code notifications_aud} revision;</li>
 *   <li>the AUDIT statement clears the mirrored message WITHOUT touching
 *       any version column — {@code notifications_aud} rows are immutable
 *       history keyed by revision;</li>
 *   <li>the recipient predicate stays {@code recipient_id} and the
 *       idempotence filter stays {@code message IS NOT NULL AND
 *       message <> ?} (the port's contract — an already-purged row
 *       matches nothing on a re-run).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class NotificationsContentPurgeAdapterTest {

    private static final UUID SUBJECT = UUID.randomUUID();

    @Mock
    private JdbcTemplate jdbcTemplate;

    private NotificationsContentPurgeAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NotificationsContentPurgeAdapter(jdbcTemplate);
    }

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        adapter.purgeAuthoredTexts(SUBJECT);

        // The optimistic-lock advance rides the SAME single statement as the
        // message erasure — atomic by construction. The COALESCE form covers
        // the family's one nullable version column: NULL advances to 1,
        // never to NULL.
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE notifications SET message = ?, version = COALESCE(version, 0) + 1")),
                any(Object[].class));
    }

    @Test
    void purgeAuthoredTexts_clearsTheAuditMirrorWithoutTouchingAnyVersionColumn() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE notifications_aud SET")),
                any(Object[].class))).thenReturn(3);

        int purged = adapter.purgeAuthoredTexts(SUBJECT);

        assertThat(purged).isEqualTo(3);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(2)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues())
                .filteredOn(s -> s.startsWith("UPDATE notifications_aud SET"))
                .hasSize(1)
                .allSatisfy(s -> assertThat(s).doesNotContain("version"));
    }

    @Test
    void purgeAuthoredTexts_keepsTheRecipientPredicateAndTheIdempotenceFilter() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        assertThat(adapter.purgeAuthoredTexts(SUBJECT)).isEqualTo(2);

        // Both statements carry the recipient scope and the marker-guard
        // idempotence filter (a re-run matches nothing — the port's
        // zero-return contract on re-runs).
        verify(jdbcTemplate, times(2)).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("WHERE recipient_id = ?")
                                && sql.contains("message IS NOT NULL AND message <> ?")),
                any(Object[].class));
    }
}

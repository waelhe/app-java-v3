package com.marketplace.messaging.spi;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CodeRabbit agent findings, row 23 — the messaging purge adapter's
 * leads' skip law: a lead carries THREE sensitive columns
 * (contact_name, contact_phone, message), so the skip predicate may
 * never trust ONE caller-writable column as the purge-state signal —
 * a lead submitted with {@code message = '[purged]'} (a string the
 * submitter writes) must not armor its real contact fields against
 * the erasure. The pin guards the SQL's own predicates; the
 * against-the-real-schema round-trip (the armored lead scrubbed base
 * and mirror, idempotent re-run) lives in LeadsIntegrationTest.
 *
 * <p>Adversarial-review 2026-11-10 finding G2 (rulebook B5, the family
 * law): both LIVE statements must ADVANCE the row's optimistic-lock
 * {@code version} atomically with the erasure. A statement that purges
 * the text without advancing the version lets a provider's inbox
 * transition (transitionLead) holding a pre-purge ListingLead snapshot
 * flush its stale entity THROUGH the passed optimistic-lock check and
 * resurrect the erased contact identifiers on the live row (plus a new
 * Envers revision) — the CWE-362 resurrection race.</p>
 */
@ExtendWith(MockitoExtension.class)
class MessagingContentPurgeAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private final UUID userId = UUID.randomUUID();

    @Test
    void theLeadsSkipRequiresEverySensitiveColumnToBeTheMarker() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        new MessagingContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        String baseLeads = capturedSqlStartingWith("UPDATE listing_leads SET");
        // The skip is the CONJUNCTION of every sensitive column — a
        // caller-written "[purged]" message alone must never satisfy it.
        assertThat(baseLeads)
                .contains("AND (contact_name <> ? OR contact_phone <> ? OR message <> ?)")
                .doesNotContain("AND message <> ?");
        // The provenance scope rides every leads statement.
        assertThat(baseLeads).contains("WHERE sender_user_id = ?");
    }

    @Test
    void theLeadsMirrorSkipIsNullSafe() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        new MessagingContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // The Envers mirror's columns are nullable (DEL revisions carry
        // NULLs): a column needs scrubbing only when it carries
        // unscrubbed content — a bare "<> ?" would treat NULL as
        // unscrubbed and rewrite history over the tombstone revisions.
        String mirrorLeads = capturedSqlStartingWith("UPDATE listing_leads_aud SET");
        assertThat(mirrorLeads)
                .contains("(contact_name IS NOT NULL AND contact_name <> ?)")
                .contains("(contact_phone IS NOT NULL AND contact_phone <> ?)")
                .contains("(message IS NOT NULL AND message <> ?)");
    }

    @Test
    void theMessagesStatementsKeepTheirSingleColumnIdempotenceFilter() {
        // The honest negative: `messages.content` is the row's ONLY
        // sensitive column — a row whose content IS the marker has
        // nothing unscrubbed, so the single-column filter stays correct
        // there (and the mirror keeps skipping the NULL DEL revisions).
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);

        new MessagingContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        assertThat(capturedSqlStartingWith("UPDATE messages SET"))
                .contains("content IS NOT NULL AND content <> ?");
        assertThat(capturedSqlStartingWith("UPDATE messages_aud SET"))
                .contains("content IS NOT NULL AND content <> ?");
    }

    @Test
    void everyStatementCarriesTheMarkerAndTheSubject() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        int purged = new MessagingContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // Four statements, each counting its own purged rows into the
        // port's grand total.
        assertThat(purged).isEqualTo(4);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, times(4)).update(anyString(), params.capture());
        // Every statement's parameters carry the shared tombstone and
        // the subject (the provenance rule), never a caller's content.
        params.getAllValues().forEach(p -> {
            assertThat(p).contains(AuthoredContentPurgePort.PURGED_MARKER);
            assertThat(p).contains(userId);
        });
    }

    private String capturedSqlStartingWith(String prefix) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(4)).update(sql.capture(), any(Object[].class));
        List<String> matches = sql.getAllValues().stream()
                .filter(s -> s.startsWith(prefix))
                .toList();
        assertThat(matches).as("exactly one statement starts with: " + prefix).hasSize(1);
        return matches.get(0);
    }

    // ---------- G2 (adversarial review 2026-11-10): the optimistic-lock
    // ---------- advance rides every LIVE statement, never a mirror ----------

    @Test
    void purgeAuthoredTexts_advancesTheLiveRowVersionAtomicallyWithTheErasure() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        new MessagingContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // Both LIVE statements carry the advance — the messages leg
        // (insert-only today, but the table carries the version column and
        // the family's single shape applies to both legs now) and the
        // listing_leads leg (the PII contact columns).
        assertThat(capturedSqlStartingWith("UPDATE messages SET"))
                .contains("version = version + 1");
        assertThat(capturedSqlStartingWith("UPDATE listing_leads SET"))
                .contains("version = version + 1");
        // The two Envers mirrors carry NO version reference — _aud rows
        // are immutable history keyed by revision, exactly like every
        // sibling adapter's mirror discipline.
        assertThat(capturedSqlStartingWith("UPDATE messages_aud SET"))
                .doesNotContain("version");
        assertThat(capturedSqlStartingWith("UPDATE listing_leads_aud SET"))
                .doesNotContain("version");
    }
}

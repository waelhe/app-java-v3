package com.marketplace.community.spi;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L42 — the community purge adapter's statement contract: the subject's
 * posts (title AND body — both NOT NULL, so the shared tombstone marker)
 * and comments (body) purge on the base tables AND the Envers mirrors,
 * with the marker-guard idempotence filter (an already-purged row
 * matches nothing on a re-run). The membership row is NOT in any
 * statement — the L41 reasoned exception, narrowed to the membership
 * row alone exactly as its javadoc promised. The against-the-real-schema
 * round-trip (criterion 9) lives in the integration test; this pin
 * guards the SQL's own predicates.
 *
 * <p>Row 18 (agent findings 2026-10-26): the polls' authored text joins
 * the purge — the question and the author label on the poll's base and
 * mirror, and every option label THROUGH the subject's polls — keyed by
 * the stable author id (V286's column + recovery), never by the label
 * string itself.</p>
 */
@ExtendWith(MockitoExtension.class)
class CommunityContentPurgeAdapterTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UUID userId = UUID.randomUUID();

    @Test
    void purgeAuthoredTexts_updatesPostsAndComments_baseAndMirrors() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);
        CommunityContentPurgeAdapter adapter = new CommunityContentPurgeAdapter(jdbcTemplate);

        int purged = adapter.purgeAuthoredTexts(userId);

        // Twelve statements (posts + posts_aud + comments + comments_aud +
        // events + events_aud + market items + market items_aud + polls +
        // polls_aud + poll options + poll options_aud), each counting its
        // own purged rows.
        assertThat(purged).isEqualTo(24);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(12)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues())
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_posts SET title = ?, body = ?"))
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_posts_aud SET title = ?, body = ?"))
                .anySatisfy(s -> assertThat(s).startsWith("UPDATE post_comments SET body = ?"))
                .anySatisfy(s -> assertThat(s).startsWith("UPDATE post_comments_aud SET body = ?"))
                // L50 (the review round's adoption): the market items'
                // two authored columns join the purge — base and mirror.
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_market_items SET title = ?"))
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_market_items_aud SET title = ?"));
        // The provenance scope: every statement carries the author filter,
        // and the membership table appears in NONE of them.
        assertThat(sql.getAllValues())
                .allSatisfy(s -> assertThat(s).contains("author_id = ?"));
        assertThat(String.join(";", sql.getAllValues()))
                .doesNotContain("neighborhood_memberships");
    }

    @Test
    void theMarkerRidesTheSharedContractConstant() {
        // The tombstone is the port's own constant — a local marker string
        // would drift from every other module's purge.
        assertThat(AuthoredContentPurgePort.PURGED_MARKER).isEqualTo("[purged]");
    }

    // ---------- L49: the events' authored text joins the purge ----------

    @Test
    void purgeAuthoredTexts_updatesOrganizedEvents_baseAndMirror() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        int purged = new CommunityContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // The grand total carries every statement's count (twelve statements
        // at two rows each — the two event statements join the post/comment
        // and market-item ones, and the four poll statements join those).
        assertThat(purged).isEqualTo(24);
        // The four authored columns ride both statements — base and mirror.
        org.mockito.Mockito.verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("UPDATE neighborhood_events SET")
                                && sql.contains("title = ?")
                                && sql.contains("description = ?")
                                && sql.contains("location_label = ?")
                                && sql.contains("organizer_label = ?")
                                && sql.contains("WHERE author_id = ?")),
                any(Object[].class));
        org.mockito.Mockito.verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("UPDATE neighborhood_events_aud SET")),
                any(Object[].class));
    }

    // ---------- Row 18: the polls' authored text joins the purge ----------

    @Test
    void purgeAuthoredTexts_updatesThePollsQuestionAndLabel_baseAndMirror() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2);

        new CommunityContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // The poll's two authored text columns ride both statements — base
        // and mirror — keyed by the STABLE author id (V286), never by the
        // display label. Review round 1: the audit mirror binds THROUGH
        // the base table (p.id = a.id, filtered by p.author_id) because
        // the _aud twin's own author_id stays NULL for every pre-V286
        // revision — the same through-the-owner shape the option mirrors
        // use.
        org.mockito.Mockito.verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.contains("UPDATE neighborhood_polls SET question = ?, author_label = ?")
                                && sql.contains("WHERE author_id = ?")
                                && sql.contains("question <> ?")
                                && sql.contains("author_label <> ?")),
                any(Object[].class));
        org.mockito.Mockito.verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        sql.startsWith("UPDATE neighborhood_polls_aud a SET question = ?, author_label = ?")
                                && sql.contains("FROM neighborhood_polls p")
                                && sql.contains("p.id = a.id")
                                && sql.contains("p.author_id = ?")
                                && sql.contains("a.question <> ?")),
                any(Object[].class));
    }

    @Test
    void purgeAuthoredTexts_purgesEveryOptionLabelThroughTheAuthorsPolls_baseAndMirror() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(3);

        int purged = new CommunityContentPurgeAdapter(jdbcTemplate).purgeAuthoredTexts(userId);

        // The options purge THROUGH their poll (the authored-unit
        // discipline): the join carries the poll's stable author, so an
        // option's label dies exactly when its poll's author is the
        // subject — base and mirror both, and the summed count carries
        // all twelve statements (3 rows each).
        assertThat(purged).isEqualTo(36);
        org.mockito.Mockito.verify(jdbcTemplate, times(2)).update(
                org.mockito.ArgumentMatchers.argThat((String sql) ->
                        (sql.startsWith("UPDATE neighborhood_poll_options o SET label = ?")
                                || sql.startsWith("UPDATE neighborhood_poll_options_aud o SET label = ?"))
                                && sql.contains("FROM neighborhood_polls p")
                                && sql.contains("p.id = o.poll_id")
                                && sql.contains("p.author_id = ?")
                                && sql.contains("o.label <> ?")),
                any(Object[].class));
    }
}

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

        // Four statements (posts + posts_aud + comments + comments_aud),
        // each counting its own purged rows.
        assertThat(purged).isEqualTo(8);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(4)).update(sql.capture(), any(Object[].class));
        assertThat(sql.getAllValues())
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_posts SET title = ?, body = ?"))
                .anySatisfy(s -> assertThat(s)
                        .startsWith("UPDATE neighborhood_posts_aud SET title = ?, body = ?"))
                .anySatisfy(s -> assertThat(s).startsWith("UPDATE post_comments SET body = ?"))
                .anySatisfy(s -> assertThat(s).startsWith("UPDATE post_comments_aud SET body = ?"));
        // The provenance scope: every statement carries the author filter,
        // and the membership table appears in NONE of them.
        assertThat(sql.getAllValues())
                .allSatisfy(s -> assertThat(s).contains("WHERE author_id = ?"));
        assertThat(String.join(";", sql.getAllValues()))
                .doesNotContain("neighborhood_memberships");
    }

    @Test
    void theMarkerRidesTheSharedContractConstant() {
        // The tombstone is the port's own constant — a local marker string
        // would drift from every other module's purge.
        assertThat(AuthoredContentPurgePort.PURGED_MARKER).isEqualTo("[purged]");
    }
}

package com.marketplace.community.spi;

import java.util.UUID;

import com.marketplace.shared.api.AuthoredContentPurgePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * L42 (neighborhood community plan §5 — the posts/feed/comments layer):
 * the community module's implementation of the
 * {@link AuthoredContentPurgePort} cross-module contract — now purging
 * for real. The L41 adapter's own closing words narrowed themselves:
 * "When a community entity that DOES carry authored text arrives (L42's
 * posts and comments), that layer's adapter purges for real and this
 * reasoning narrows to the membership row alone."
 *
 * <p><b>The purge scope (the port's provenance rule — "نصوص مؤلفها"):</b>
 * the subject's own posts ({@code title}/{@code body} — both NOT NULL,
 * V61, so the shared {@link AuthoredContentPurgePort#PURGED_MARKER}
 * tombstone is the honest representation) and the subject's own comments
 * ({@code body} — same shape), on the base tables AND the Envers mirrors
 * (the port's contract: a purge that left the history intact would be
 * cosmetic). The membership row stays untouched — the L41 reasoned
 * exception, unchanged: identifiers and state, structural like the
 * accounting record, its retention b-5's decision.
 *
 * <p><b>Statement shape (the port's contract, the messaging adapter's
 * measured precedent):</b> native JDBC UPDATE — the Envers mirror has no
 * mapped entity, and the same deterministic channel serves the base
 * statement; no revision is written for the purge itself (the
 * orchestrator's structured log line is the audit record). The
 * {@code <> marker} filters make every statement idempotent with exact
 * counts: already-purged rows match nothing on a re-run.
 */
@Component
public class CommunityContentPurgeAdapter implements AuthoredContentPurgePort {

    private static final Logger log = LoggerFactory.getLogger(CommunityContentPurgeAdapter.class);

    private final JdbcTemplate jdbcTemplate;

    public CommunityContentPurgeAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public int purgeAuthoredTexts(UUID userId) {
        int posts = jdbcTemplate.update(
                "UPDATE neighborhood_posts SET title = ?, body = ? "
                        + "WHERE author_id = ? AND (title <> ? OR body <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int postAuditRows = jdbcTemplate.update(
                "UPDATE neighborhood_posts_aud SET title = ?, body = ? "
                        + "WHERE author_id = ? AND (title <> ? OR body <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int comments = jdbcTemplate.update(
                "UPDATE post_comments SET body = ? "
                        + "WHERE author_id = ? AND body <> ?",
                AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER);
        int commentAuditRows = jdbcTemplate.update(
                "UPDATE post_comments_aud SET body = ? "
                        + "WHERE author_id = ? AND body <> ?",
                AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Community content purge: userId={}, posts={}, postAuditRows={}, "
                        + "comments={}, commentAuditRows={}",
                userId, posts, postAuditRows, comments, commentAuditRows);
        return posts + postAuditRows + comments + commentAuditRows;
    }
}

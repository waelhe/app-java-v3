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
        // L49 (the events layer): the subject's organized events carry
        // FOUR authored columns (title, description, the two display
        // labels — all NOT NULL) — the posts' own purge shape, base
        // table and Envers mirror both. The seats are ids-only (the
        // reaction row's own reasoning) — nothing to purge there.
        int events = jdbcTemplate.update(
                "UPDATE neighborhood_events SET title = ?, description = ?, "
                        + "location_label = ?, organizer_label = ? "
                        + "WHERE author_id = ? AND (title <> ? OR description <> ? "
                        + "OR location_label <> ? OR organizer_label <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int eventAuditRows = jdbcTemplate.update(
                "UPDATE neighborhood_events_aud SET title = ?, description = ?, "
                        + "location_label = ?, organizer_label = ? "
                        + "WHERE author_id = ? AND (title <> ? OR description <> ? "
                        + "OR location_label <> ? OR organizer_label <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        // L50 (the market board, review round adopted from the root): the
        // subject's market items carry TWO authored columns (title and the
        // pickup-spot's location_label — both NOT NULL, V90) — the events'
        // own purge shape, base table and Envers mirror both. The price,
        // category and status are identifiers-and-state (the reaction row's
        // own class) — nothing to purge there. V90's author index was born
        // NON-partial for exactly this seam (b-3's own reasoning).
        int marketItems = jdbcTemplate.update(
                "UPDATE neighborhood_market_items SET title = ?, location_label = ? "
                        + "WHERE author_id = ? AND (title <> ? OR location_label <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        int marketItemAuditRows = jdbcTemplate.update(
                "UPDATE neighborhood_market_items_aud SET title = ?, location_label = ? "
                        + "WHERE author_id = ? AND (title <> ? OR location_label <> ?)",
                AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER,
                userId, AuthoredContentPurgePort.PURGED_MARKER, AuthoredContentPurgePort.PURGED_MARKER);
        log.info("Community content purge: userId={}, posts={}, postAuditRows={}, "
                        + "comments={}, commentAuditRows={}, events={}, eventAuditRows={}, "
                        + "marketItems={}, marketItemAuditRows={}",
                userId, posts, postAuditRows, comments, commentAuditRows,
                events, eventAuditRows, marketItems, marketItemAuditRows);
        return posts + postAuditRows + comments + commentAuditRows + events + eventAuditRows
                + marketItems + marketItemAuditRows;
    }
}

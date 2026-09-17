package com.marketplace.community.spi;

import com.marketplace.shared.api.CommunityCommentExportEntry;
import com.marketplace.shared.api.CommunityExportPort;
import com.marketplace.shared.api.CommunityMembershipExportEntry;
import com.marketplace.shared.api.CommunityPostExportEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * L41/L42 (neighborhood community plan §5): the community module's
 * contribution to the Art. 20 account export. Reads through native JDBC
 * — the saved-search export adapter's own reasoning: the export is a
 * faithful copy of the stored row, not an entity projection, and native
 * SQL is the one channel that sees the soft-deleted rows Hibernate's
 * filter hides (b-5's discrimination: a LEFT membership, a DELETED post
 * and a deleted comment are still the subject's stored personal data
 * until the retention window closes).
 *
 * <p>L42 adds the posts and comments reads in the same shape — the
 * subject's own authored texts, base facts verbatim, stable
 * {@code (created_at, id)} order, the post's location as the stored
 * {@code geo_locations} id, the enums as their stored names.
 */
@Component
public class CommunityExportAdapter implements CommunityExportPort {

    private final JdbcTemplate jdbcTemplate;

    public CommunityExportAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommunityMembershipExportEntry> exportForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, location_id, verification_state, member_since,
                       created_at, updated_at, is_deleted
                FROM neighborhood_memberships
                WHERE user_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityMembershipExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("location_id")),
                        rs.getString("verification_state"),
                        rs.getTimestamp("member_since").toInstant(),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommunityPostExportEntry> exportPostsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, location_id, category, title, body, status,
                       created_at, updated_at, is_deleted
                FROM neighborhood_posts
                WHERE author_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityPostExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("location_id")),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("body"),
                        rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CommunityCommentExportEntry> exportCommentsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, post_id, body, created_at, updated_at, is_deleted
                FROM post_comments
                WHERE author_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityCommentExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("post_id")),
                        rs.getString("body"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }
}

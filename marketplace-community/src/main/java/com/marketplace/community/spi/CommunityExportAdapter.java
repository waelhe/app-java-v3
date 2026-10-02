package com.marketplace.community.spi;

import com.marketplace.shared.api.CommunityCommentExportEntry;
import com.marketplace.shared.api.CommunityEventExportEntry;
import com.marketplace.shared.api.CommunityEventSeatExportEntry;
import com.marketplace.shared.api.CommunityExportPort;
import com.marketplace.shared.api.CommunityGroupMembershipExportEntry;
import com.marketplace.shared.api.CommunityMarketItemExportEntry;
import com.marketplace.shared.api.CommunityMembershipExportEntry;
import com.marketplace.shared.api.CommunityPostExportEntry;
import com.marketplace.shared.api.CommunityReactionExportEntry;
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
 *
 * <p>L47 + the #484 review round adds the reactions read in the same
 * shape — which posts the subject thanked and when, live and removed
 * alike (the reaction layer had ridden V73 with no export leg: a member
 * requesting their data received no record of their reactions).
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

    @Override
    @Transactional(readOnly = true)
    public List<CommunityReactionExportEntry> exportReactionsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, post_id, created_at, updated_at, is_deleted
                FROM post_reactions
                WHERE member_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityReactionExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("post_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    /**
     * L49 (the events layer): the subject's organized events — the same
     * native-JDBC faithful-copy read as the posts (the soft-deleted
     * rows included, b-5's discrimination), the four authored columns
     * verbatim, the enums as their stored names.
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommunityEventExportEntry> exportEventsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, location_id, category, title, description,
                       starts_at, ends_at, location_label, organizer_label,
                       capacity, registration, featured,
                       created_at, updated_at, is_deleted
                FROM neighborhood_events
                WHERE author_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityEventExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("location_id")),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("description"),
                        rs.getTimestamp("starts_at").toInstant(),
                        rs.getTimestamp("ends_at") == null
                                ? null : rs.getTimestamp("ends_at").toInstant(),
                        rs.getString("location_label"),
                        rs.getString("organizer_label"),
                        rs.getObject("capacity", Integer.class),
                        rs.getString("registration"),
                        rs.getBoolean("featured"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    /**
     * L49: the subject's seats — held and freed — the identifiers-and-
     * timestamps read (the reaction row's own class; no authored text
     * exists to copy).
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommunityEventSeatExportEntry> exportEventSeatsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, event_id, created_at, updated_at, is_deleted
                FROM event_rsvps
                WHERE member_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityEventSeatExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("event_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    /**
     * L50 (the market board, the review round's root fix): the subject's
     * published items — the same native-JDBC faithful-copy read as the
     * posts (the withdrawn rows included, b-5's discrimination — V90's
     * author index is NON-partial for exactly this scan), the two
     * authored columns plus the stored pricing declaration verbatim,
     * the enums as their stored names.
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommunityMarketItemExportEntry> exportMarketItemsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, location_id, category, title, item_condition,
                       price_cents, price_currency, status, location_label,
                       created_at, updated_at, is_deleted
                FROM neighborhood_market_items
                WHERE author_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityMarketItemExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("location_id")),
                        rs.getString("category"),
                        rs.getString("title"),
                        rs.getString("item_condition"),
                        rs.getObject("price_cents", Long.class),
                        rs.getString("price_currency"),
                        rs.getString("status"),
                        rs.getString("location_label"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    /**
     * L51 (the neighbors groups): the subject's group memberships —
     * live and left — the identifiers-and-timestamps read (the seat
     * row's own class; no authored text exists to copy). Born with the
     * wave (the L50 market review's own lesson: the b-2 seam rides the
     * layer's own PR, never a later one); V91's member index is
     * NON-partial for exactly this scan.
     */
    @Override
    @Transactional(readOnly = true)
    public List<CommunityGroupMembershipExportEntry> exportGroupMembershipsForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, group_id, created_at, updated_at, is_deleted
                FROM neighborhood_group_memberships
                WHERE member_id = ?
                ORDER BY created_at, id
                """,
                (rs, rowNum) -> new CommunityGroupMembershipExportEntry(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("group_id")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant(),
                        rs.getBoolean("is_deleted")),
                userId);
    }
}

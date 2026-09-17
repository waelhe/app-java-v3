package com.marketplace.community.spi;

import com.marketplace.shared.api.CommunityExportPort;
import com.marketplace.shared.api.CommunityMembershipExportEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * L41 (neighborhood community plan §5): the community module's
 * contribution to the Art. 20 account export. Reads through native JDBC
 * — the saved-search export adapter's own reasoning: the export is a
 * faithful copy of the stored row, not an entity projection, and native
 * SQL is the one channel that sees the soft-deleted rows Hibernate's
 * filter hides (b-5's discrimination: a LEFT membership is still the
 * subject's stored personal data — their place declaration — until the
 * retention window closes).
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
}

package com.marketplace.catalog.spi;

import com.marketplace.shared.api.ListingFavoriteExportEntry;
import com.marketplace.shared.api.ListingFavoritesExportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * W3 (yelp-level plan §5 — G19, the review round's export leg): the
 * catalog module's implementation of the {@link ListingFavoritesExportPort}
 * cross-module contract. Reads through native JDBC — the community
 * export adapter's own reasoning: the export is a faithful copy of the
 * stored row, not an entity projection, and native SQL is the one
 * channel that sees the soft-deleted rows Hibernate's filter hides
 * (b-5's discrimination: a withdrawn favorite is still the subject's
 * stored relation until the retention window closes).
 */
@Component
@Transactional(readOnly = true)
public class ListingFavoritesExportAdapter implements ListingFavoritesExportPort {

    private final JdbcTemplate jdbcTemplate;

    public ListingFavoritesExportAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ListingFavoriteExportEntry> exportForOwner(UUID userId) {
        return jdbcTemplate.query(
                """
                SELECT id, listing_id, created_at, updated_at, is_deleted
                FROM listing_favorites
                WHERE user_id = ?
                ORDER BY created_at ASC, id ASC
                """,
                (rs, rowNum) -> new ListingFavoriteExportEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("listing_id", UUID.class),
                        toInstant(rs, "created_at"),
                        toInstant(rs, "updated_at"),
                        rs.getBoolean("is_deleted")),
                userId);
    }

    private static java.time.Instant toInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}

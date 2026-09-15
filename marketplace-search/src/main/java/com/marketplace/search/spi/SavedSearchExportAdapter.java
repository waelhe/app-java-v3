package com.marketplace.search.spi;

import com.marketplace.shared.api.SavedSearchExportEntry;
import com.marketplace.shared.api.SavedSearchExportPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * L35 (realestate systems plan §5 — criterion 7): the search module's
 * contribution to the Art. 20 account export. Reads through native JDBC
 * (the purge adapter's own reasoning: the export is a faithful copy of
 * the stored JSONB, not an entity projection) — the criteria travel as
 * their canonical JSON text, soft-deleted searches included (the port's
 * documented b-5 discrimination: surface deletion is a visibility flag,
 * not an erasure).
 */
@Component
public class SavedSearchExportAdapter implements SavedSearchExportPort {

    private final JdbcTemplate jdbcTemplate;

    public SavedSearchExportAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedSearchExportEntry> exportForOwner(UUID userId) {
        return jdbcTemplate.query(
                "SELECT id, criteria::text, alert_enabled, last_matched_at, created_at "
                        + "FROM saved_searches WHERE user_id = ? ORDER BY created_at, id",
                (rs, rowNum) -> new SavedSearchExportEntry(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("criteria"),
                        rs.getBoolean("alert_enabled"),
                        rs.getTimestamp("last_matched_at") == null
                                ? null : rs.getTimestamp("last_matched_at").toInstant(),
                        rs.getTimestamp("created_at").toInstant()),
                userId);
    }
}

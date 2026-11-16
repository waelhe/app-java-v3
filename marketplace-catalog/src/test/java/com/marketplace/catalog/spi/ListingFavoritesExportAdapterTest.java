package com.marketplace.catalog.spi;

import com.marketplace.shared.api.ListingFavoriteExportEntry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3 (G19, the review round's export leg): the favorites export adapter's
 * contract — the native read by owner (the one channel that sees the
 * soft-deleted rows Hibernate's filter hides), the stable
 * {@code (created_at, id)} order riding the SQL itself, and the row's
 * verbatim mapping (the withdrawn favorite's {@code deleted} flag is the
 * b-5 discrimination the export exists to carry).
 */
class ListingFavoritesExportAdapterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ListingFavoritesExportAdapter adapter = new ListingFavoritesExportAdapter(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void readsByOwnerIncludingWithdrawnRowsInStableOrder() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID favoriteId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        Instant saved = Instant.parse("2026-10-01T10:00:00Z");
        Instant updated = Instant.parse("2026-10-02T09:30:00Z");
        ResultSet rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(favoriteId);
        when(rs.getObject("listing_id", UUID.class)).thenReturn(listingId);
        when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(saved));
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(updated));
        when(rs.getBoolean("is_deleted")).thenReturn(true);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(owner)))
                .thenAnswer(inv -> {
                    RowMapper<ListingFavoriteExportEntry> mapper =
                            (RowMapper<ListingFavoriteExportEntry>) inv.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<ListingFavoriteExportEntry> entries = adapter.exportForOwner(owner);

        assertThat(entries).hasSize(1);
        ListingFavoriteExportEntry entry = entries.getFirst();
        assertThat(entry.id()).isEqualTo(favoriteId);
        assertThat(entry.listingId()).isEqualTo(listingId);
        assertThat(entry.savedAt()).isEqualTo(saved);
        assertThat(entry.updatedAt()).isEqualTo(updated);
        // The b-5 discrimination: a withdrawn favorite still exports.
        assertThat(entry.deleted()).isTrue();
        // The read is native SQL over the favorites table, by owner, in the
        // stable (created_at, id) order the export contract names.
        verify(jdbc).query(
                org.mockito.ArgumentMatchers.argThat((String sql) -> sql.contains("FROM listing_favorites")
                        && sql.contains("WHERE user_id = ?")
                        && sql.contains("ORDER BY created_at ASC, id ASC")
                        && sql.contains("is_deleted")),
                any(RowMapper.class), eq(owner));
    }
}
